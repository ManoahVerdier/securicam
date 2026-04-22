import { Injectable, OnDestroy } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Subject, Observable, Subscription, firstValueFrom } from 'rxjs';
import { environment } from '../../environments/environment';
import { SignalingService } from './signaling.service';
import { CameraResponse, CameraStatus, WebRtcAnswer, WebRtcIceCandidate } from '../models';

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
  private static readonly CAMERA_READY_TIMEOUT_MS = 30000;
  private static readonly CAMERA_STATUS_POLL_INTERVAL_MS = 2000;

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
    console.info(`[WebRTC] Starting connection flow for camera ${cameraId}`);

    // Close existing connection if any
    this.disconnectFromCamera(cameraId);

    // Join the signaling channel first so offers are never missed
    this.signalingService.joinCameraChannel(cameraId);

    const pc = new RTCPeerConnection({
      iceServers: environment.iceServers
    });

    this.peerConnections.set(cameraId, pc);

    // Handle ICE candidates
    pc.onicecandidate = (event) => {
      if (event.candidate) {
        console.info(`[WebRTC] Local ICE candidate generated for camera ${cameraId}`);
        this.sendIceCandidate(cameraId, event.candidate);
      }
    };

    // Handle remote stream
    pc.ontrack = (event) => {
      const stream = event.streams[0];
      if (stream) {
        console.info(`[WebRTC] Remote stream received for camera ${cameraId}`);
        this.remoteStreams.set(cameraId, stream);
        this.streamSubject.next({ cameraId, stream });
        this.emitState(cameraId, pc, true);
      }
    };

    // Handle connection state changes
    pc.onconnectionstatechange = () => {
      console.info(`[WebRTC] Connection state for camera ${cameraId}: ${pc.connectionState}`);
      this.emitState(cameraId, pc, this.remoteStreams.has(cameraId));
    };

    pc.oniceconnectionstatechange = () => {
      console.info(`[WebRTC] ICE connection state for camera ${cameraId}: ${pc.iceConnectionState}`);
      this.emitState(cameraId, pc, this.remoteStreams.has(cameraId));

      if (pc.iceConnectionState === 'failed') {
        console.warn(`[WebRTC] ICE failed for camera ${cameraId}, restarting ICE`);
        pc.restartIce();
      }
    };

    try {
      await this.requestStreamStart(cameraId);
      const cameraStatus = await this.waitForCameraReady(cameraId, WebrtcService.CAMERA_READY_TIMEOUT_MS);
      console.info(`[WebRTC] Camera ${cameraId} is ready with status "${cameraStatus}"`);
    } catch (error) {
      this.disconnectFromCamera(cameraId);
      throw error;
    }
  }

  private async requestStreamStart(cameraId: number): Promise<void> {
    console.info(`[WebRTC] Requesting stream start for camera ${cameraId}`);
    try {
      await firstValueFrom(this.http.post(`${environment.apiUrl}/webrtc/start`, {
        camera_id: cameraId
      }));
      console.info(`[WebRTC] Stream start request accepted for camera ${cameraId}`);
    } catch (error) {
      console.error(`[WebRTC] Stream start request failed for camera ${cameraId}`, error);
    }
  }

  private async waitForCameraReady(cameraId: number, timeoutMs: number): Promise<CameraStatus> {
    const startedAt = Date.now();

    while (Date.now() - startedAt < timeoutMs) {
      try {
        const status = await this.getCameraStatus(cameraId);
        console.info(`[WebRTC] Camera ${cameraId} polled status: ${status}`);

        if (status === 'online' || status === 'streaming' || status === 'recording') {
          return status;
        }
      } catch (error) {
        console.warn(`[WebRTC] Camera ${cameraId} status polling failed`, error);
      }

      await this.delay(WebrtcService.CAMERA_STATUS_POLL_INTERVAL_MS);
    }

    throw new Error(`Camera ${cameraId} did not become ready within ${timeoutMs / 1000}s`);
  }

  private async getCameraStatus(cameraId: number): Promise<CameraStatus> {
    const response = await firstValueFrom(
      this.http.get<CameraResponse>(`${environment.apiUrl}/cameras/${cameraId}`)
    );
    return response.camera.status;
  }

  private delay(ms: number): Promise<void> {
    return new Promise(resolve => setTimeout(resolve, ms));
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
    console.info(`[WebRTC] Offer received for camera ${cameraId}`);
    let pc = this.peerConnections.get(cameraId);

    if (!pc) {
      console.info(`[WebRTC] No peer connection for camera ${cameraId}, creating one from offer`);
      try {
        await this.connectToCamera(cameraId);
      } catch (error) {
        console.error(`[WebRTC] Failed to initialize connection for camera ${cameraId} from offer`, error);
        return;
      }
      pc = this.peerConnections.get(cameraId);
    }

    if (!pc) {
      console.error('Failed to create peer connection');
      return;
    }

    try {
      console.info(`[WebRTC] Applying remote offer for camera ${cameraId}`);
      await pc.setRemoteDescription(new RTCSessionDescription({ type: 'offer', sdp }));
      const answer = await pc.createAnswer();
      await pc.setLocalDescription(answer);
      console.info(`[WebRTC] Sending answer for camera ${cameraId}`);

      // Send answer back through API
      this.sendAnswer(cameraId, answer.sdp!);
    } catch (error) {
      console.error('Error handling offer:', error);
    }
  }

  private async handleIceCandidate(cameraId: number, candidate: RTCIceCandidateInit): Promise<void> {
    const pc = this.peerConnections.get(cameraId);
    if (!pc) {
      console.warn(`[WebRTC] Ignoring ICE candidate for camera ${cameraId}: no peer connection`);
      return;
    }

    try {
      console.info(`[WebRTC] Applying remote ICE candidate for camera ${cameraId}`);
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
