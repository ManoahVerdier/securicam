import { Injectable, OnDestroy } from '@angular/core';
import { Subject, Observable } from 'rxjs';
import Echo from 'laravel-echo';
import Pusher from 'pusher-js';
import { environment } from '../../environments/environment';
import { AuthService } from './auth.service';
import { WebRtcOffer, WebRtcAnswer, WebRtcIceCandidate, Capture } from '../models';

@Injectable({
  providedIn: 'root'
})
export class SignalingService implements OnDestroy {
  private echo: any = null;
  private channels: Map<number, any> = new Map();

  private offerSubject = new Subject<WebRtcOffer>();
  private answerSubject = new Subject<WebRtcAnswer>();
  private iceCandidateSubject = new Subject<WebRtcIceCandidate>();
  private connectionStatusSubject = new Subject<boolean>();
  private captureCreatedSubject = new Subject<{ cameraId: number; capture: Capture }>();
  private cameraOnlineSubject = new Subject<{ cameraId: number }>();
  private cameraOfflineSubject = new Subject<{ cameraId: number }>();

  readonly offers$: Observable<WebRtcOffer> = this.offerSubject.asObservable();
  readonly answers$: Observable<WebRtcAnswer> = this.answerSubject.asObservable();
  readonly iceCandidates$: Observable<WebRtcIceCandidate> = this.iceCandidateSubject.asObservable();
  readonly connectionStatus$: Observable<boolean> = this.connectionStatusSubject.asObservable();
  /** Stream of newly uploaded captures, broadcast by the backend on `camera.{id}`. */
  readonly captureCreated$: Observable<{ cameraId: number; capture: Capture }> =
    this.captureCreatedSubject.asObservable();
  /** Fires when a camera reconnects after being offline (first heartbeat post-offline). */
  readonly cameraOnline$: Observable<{ cameraId: number }> =
    this.cameraOnlineSubject.asObservable();
  /** Fires when the scheduler marks a camera offline via `camera.offline` broadcast. */
  readonly cameraOffline$: Observable<{ cameraId: number }> =
    this.cameraOfflineSubject.asObservable();

  constructor(private authService: AuthService) {}

  connect(): void {
    if (this.echo) {
      console.info('[Signaling] WebSocket already initialized');
      return;
    }

    const token = this.authService.getToken();
    if (!token) {
      console.error('[Signaling] No auth token available');
      return;
    }

    try {
      console.info('[Signaling] Initializing WebSocket connection');
      (window as any).Pusher = Pusher;

      // Initialize Laravel Echo with Pusher/Reverb
      this.echo = new Echo({
        broadcaster: 'reverb',
        key: environment.wsKey,
        wsHost: environment.wsHost,
        wsPort: environment.wsPort,
        wssPort: environment.wsPort,
        forceTLS: environment.wsScheme === 'https',
        enabledTransports: ['ws', 'wss'],
        authEndpoint: `${environment.apiUrl.replace('/api', '')}/broadcasting/auth`,
        auth: {
          headers: {
            Authorization: `Bearer ${token}`
          }
        }
      });

      const connection = this.echo.connector.pusher.connection;

      connection.bind('connecting', () => {
        console.info('[Signaling] WebSocket connecting');
      });

      connection.bind('connected', () => {
        console.info('[Signaling] WebSocket connected');
        this.connectionStatusSubject.next(true);
      });

      connection.bind('disconnected', () => {
        console.warn('[Signaling] WebSocket disconnected');
        this.connectionStatusSubject.next(false);
      });

      connection.bind('unavailable', () => {
        console.error('[Signaling] WebSocket unavailable');
        this.connectionStatusSubject.next(false);
      });

      connection.bind('failed', () => {
        console.error('[Signaling] WebSocket failed');
        this.connectionStatusSubject.next(false);
      });

      connection.bind('state_change', (states: { previous: string; current: string }) => {
        console.info(`[Signaling] WebSocket state: ${states.previous} -> ${states.current}`);
      });

      connection.bind('error', (error: unknown) => {
        console.error('[Signaling] WebSocket error', error);
        this.connectionStatusSubject.next(false);
      });
    } catch (error) {
      console.error('[Signaling] Failed to initialize Echo', error);
    }
  }

  disconnect(): void {
    if (this.echo) {
      this.channels.forEach((_, cameraId) => {
        this.leaveChannel(cameraId);
      });
      this.echo.disconnect();
      this.echo = null;
    }
  }

  joinCameraChannel(cameraId: number): void {
    if (!this.echo || this.channels.has(cameraId)) {
      if (!this.echo) {
        console.warn(`[Signaling] Cannot join camera.${cameraId}: WebSocket not connected`);
      }
      return;
    }

    console.info(`[Signaling] Joining camera.${cameraId} channel`);
    const channel = this.echo.private(`camera.${cameraId}`);

    channel.listen('.webrtc.offer', (data: WebRtcOffer) => {
      console.info(`[Signaling] Offer received for camera ${cameraId}`);
      this.offerSubject.next(data);
    });

    channel.listen('.webrtc.answer', (data: WebRtcAnswer) => {
      console.info(`[Signaling] Answer received for camera ${cameraId}`);
      this.answerSubject.next(data);
    });

    channel.listen('.webrtc.ice-candidate', (data: WebRtcIceCandidate) => {
      console.info(`[Signaling] ICE candidate received for camera ${cameraId}`);
      this.iceCandidateSubject.next(data);
    });

    channel.listen('.capture.created', (data: { capture: Capture }) => {
      console.info(`[Signaling] Capture created for camera ${cameraId}`, data.capture?.id);
      if (data?.capture) {
        this.captureCreatedSubject.next({ cameraId, capture: data.capture });
      }
    });

    channel.listen('.camera.online', (data: { camera_id: number }) => {
      console.info(`[Signaling] Camera ${data.camera_id} reconnected`);
      this.cameraOnlineSubject.next({ cameraId: data.camera_id });
    });

    channel.listen('.camera.offline', (data: { camera_id: number }) => {
      console.warn(`[Signaling] Camera ${data.camera_id} went offline`);
      this.cameraOfflineSubject.next({ cameraId: data.camera_id });
    });

    this.channels.set(cameraId, channel);
  }

  leaveChannel(cameraId: number): void {
    if (this.echo && this.channels.has(cameraId)) {
      console.info(`[Signaling] Leaving camera.${cameraId} channel`);
      this.echo.leave(`camera.${cameraId}`);
      this.channels.delete(cameraId);
    }
  }

  ngOnDestroy(): void {
    this.disconnect();
    this.offerSubject.complete();
    this.answerSubject.complete();
    this.iceCandidateSubject.complete();
    this.connectionStatusSubject.complete();
    this.captureCreatedSubject.complete();
    this.cameraOnlineSubject.complete();
    this.cameraOfflineSubject.complete();
  }
}
