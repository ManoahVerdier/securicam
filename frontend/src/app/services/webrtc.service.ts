import { Injectable, OnDestroy } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Subject, Observable, Subscription } from 'rxjs';
import { environment } from '../../environments/environment';
import { SignalingService } from './signaling.service';
import { WebRtcAnswer, WebRtcIceCandidate } from '../models';

export interface WebRtcState {
  cameraId: number;
  connectionState: RTCPeerConnectionState;
  iceConnectionState: RTCIceConnectionState;
  hasRemoteStream: boolean;
}

@Injectable({
  providedIn: 'root'
})
export class WebrtcService implements OnDestroy {
  private peerConnections: Map<number, RTCPeerConnection> = new Map();
  private remoteStreams: Map<number, MediaStream> = new Map();

  private stateSubject = new Subject<WebRtcState>();
  private streamSubject = new Subject<{ cameraId: number; stream: MediaStream }>();

  private subscriptions: Subscription[] = [];

  readonly state$: Observable<WebRtcState> = this.stateSubject.asObservable();
  readonly stream$: Observable<{ cameraId: number; stream: MediaStream }> = this.streamSubject.asObservable();

  constructor(
    private http: HttpClient,
    private signalingService: SignalingService
  ) {
    this.setupSignalingListeners();
  }

  private setupSignalingListeners(): void {
    // Listen for WebRTC offers from cameras
    this.subscriptions.push(
      this.signalingService.offers$.subscribe(offer => {
        this.handleOffer(offer.camera_id, offer.sdp);
      })
    );

    // Listen for ICE candidates
    this.subscriptions.push(
      this.signalingService.iceCandidates$.subscribe(candidate => {
        this.handleIceCandidate(candidate.camera_id, candidate.candidate);
      })
    );
  }

  async connectToCamera(cameraId: number): Promise<void> {
    // Close existing connection if any
    this.disconnectFromCamera(cameraId);

    // Join the signaling channel first so offers are never missed
    this.signalingService.joinCameraChannel(cameraId);

    this.requestStreamStart(cameraId);
    const pc = new RTCPeerConnection({
      iceServers: environment.iceServers
    });

    this.peerConnections.set(cameraId, pc);

    // Handle ICE candidates
    pc.onicecandidate = (event) => {
      if (event.candidate) {
        this.sendIceCandidate(cameraId, event.candidate);
      }
    };

    // Handle remote stream
    pc.ontrack = (event) => {
      const stream = event.streams[0];
      if (stream) {
        this.remoteStreams.set(cameraId, stream);
        this.streamSubject.next({ cameraId, stream });
        this.emitState(cameraId, pc, true);
      }
    };

    // Handle connection state changes
    pc.onconnectionstatechange = () => {
      this.emitState(cameraId, pc, this.remoteStreams.has(cameraId));
    };

    pc.oniceconnectionstatechange = () => {
      this.emitState(cameraId, pc, this.remoteStreams.has(cameraId));

      if (pc.iceConnectionState === 'failed') {
        pc.restartIce();
      }
    };
  }

  private requestStreamStart(cameraId: number): void {
    this.http.post(`${environment.apiUrl}/webrtc/start`, {
      camera_id: cameraId
    }).subscribe({
      error: (error) => console.error('Error requesting camera stream start:', error)
    });
  }

  disconnectFromCamera(cameraId: number): void {
    const pc = this.peerConnections.get(cameraId);
    if (pc) {
      pc.close();
      this.peerConnections.delete(cameraId);
    }

    const stream = this.remoteStreams.get(cameraId);
    if (stream) {
      stream.getTracks().forEach(track => track.stop());
      this.remoteStreams.delete(cameraId);
    }

    this.signalingService.leaveChannel(cameraId);
  }

  getStream(cameraId: number): MediaStream | undefined {
    return this.remoteStreams.get(cameraId);
  }

  private async handleOffer(cameraId: number, sdp: string): Promise<void> {
    let pc = this.peerConnections.get(cameraId);

    if (!pc) {
      await this.connectToCamera(cameraId);
      pc = this.peerConnections.get(cameraId);
    }

    if (!pc) {
      console.error('Failed to create peer connection');
      return;
    }

    try {
      await pc.setRemoteDescription(new RTCSessionDescription({ type: 'offer', sdp }));
      const answer = await pc.createAnswer();
      await pc.setLocalDescription(answer);

      // Send answer back through API
      this.sendAnswer(cameraId, answer.sdp!);
    } catch (error) {
      console.error('Error handling offer:', error);
    }
  }

  private async handleIceCandidate(cameraId: number, candidate: RTCIceCandidateInit): Promise<void> {
    const pc = this.peerConnections.get(cameraId);
    if (!pc) {
      return;
    }

    try {
      await pc.addIceCandidate(new RTCIceCandidate(candidate));
    } catch (error) {
      console.error('Error adding ICE candidate:', error);
    }
  }

  private sendAnswer(cameraId: number, sdp: string): void {
    const answer: WebRtcAnswer = {
      camera_id: cameraId,
      sdp,
      type: 'answer'
    };

    this.http.post(`${environment.apiUrl}/webrtc/answer`, answer).subscribe({
      error: (error) => console.error('Error sending answer:', error)
    });
  }

  private sendIceCandidate(cameraId: number, candidate: RTCIceCandidate): void {
    const data: WebRtcIceCandidate = {
      camera_id: cameraId,
      candidate: candidate.toJSON()
    };

    this.http.post(`${environment.apiUrl}/webrtc/ice-candidate`, data).subscribe({
      error: (error) => console.error('Error sending ICE candidate:', error)
    });
  }

  private emitState(cameraId: number, pc: RTCPeerConnection, hasRemoteStream: boolean): void {
    this.stateSubject.next({
      cameraId,
      connectionState: pc.connectionState,
      iceConnectionState: pc.iceConnectionState,
      hasRemoteStream
    });
  }

  ngOnDestroy(): void {
    this.peerConnections.forEach((_, cameraId) => {
      this.disconnectFromCamera(cameraId);
    });

    this.subscriptions.forEach(sub => sub.unsubscribe());
    this.stateSubject.complete();
    this.streamSubject.complete();
  }
}
