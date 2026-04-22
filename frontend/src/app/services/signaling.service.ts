import { Injectable, OnDestroy } from '@angular/core';
import { Subject, Observable } from 'rxjs';
import Echo from 'laravel-echo';
import Pusher from 'pusher-js';
import { environment } from '../../environments/environment';
import { AuthService } from './auth.service';
import { WebRtcOffer, WebRtcAnswer, WebRtcIceCandidate } from '../models';

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

  readonly offers$: Observable<WebRtcOffer> = this.offerSubject.asObservable();
  readonly answers$: Observable<WebRtcAnswer> = this.answerSubject.asObservable();
  readonly iceCandidates$: Observable<WebRtcIceCandidate> = this.iceCandidateSubject.asObservable();
  readonly connectionStatus$: Observable<boolean> = this.connectionStatusSubject.asObservable();

  constructor(private authService: AuthService) {}

  connect(): void {
    if (this.echo) {
      return;
    }

    const token = this.authService.getToken();
    if (!token) {
      console.error('No auth token available');
      return;
    }

    try {
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

      this.echo.connector.pusher.connection.bind('connected', () => {
        this.connectionStatusSubject.next(true);
      });

      this.echo.connector.pusher.connection.bind('disconnected', () => {
        this.connectionStatusSubject.next(false);
      });

    } catch (error) {
      console.error('Failed to initialize Echo:', error);
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
      return;
    }

    const channel = this.echo.private(`camera.${cameraId}`);

    channel.listen('.webrtc.offer', (data: WebRtcOffer) => {
      this.offerSubject.next(data);
    });

    channel.listen('.webrtc.answer', (data: WebRtcAnswer) => {
      this.answerSubject.next(data);
    });

    channel.listen('.webrtc.ice-candidate', (data: WebRtcIceCandidate) => {
      this.iceCandidateSubject.next(data);
    });

    this.channels.set(cameraId, channel);
  }

  leaveChannel(cameraId: number): void {
    if (this.echo && this.channels.has(cameraId)) {
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
  }
}
