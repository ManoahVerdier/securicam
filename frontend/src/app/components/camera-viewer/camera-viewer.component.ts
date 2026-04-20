import { Component, Input, Output, EventEmitter, OnInit, OnDestroy, ViewChild, ElementRef, AfterViewInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Subscription } from 'rxjs';
import { Camera } from '../../models';
import { WebrtcService, WebRtcState } from '../../services';

@Component({
  selector: 'app-camera-viewer',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './camera-viewer.component.html',
  styleUrl: './camera-viewer.component.scss'
})
export class CameraViewerComponent implements OnInit, OnDestroy, AfterViewInit {
  @Input({ required: true }) camera!: Camera;
  @Output() connectionStateChange = new EventEmitter<WebRtcState>();

  @ViewChild('videoElement') videoElement!: ElementRef<HTMLVideoElement>;

  connectionState: RTCPeerConnectionState = 'new';
  iceConnectionState: RTCIceConnectionState = 'new';
  isLoading = true;
  hasError = false;
  errorMessage = '';

  private subscriptions: Subscription[] = [];

  constructor(private webrtcService: WebrtcService) {}

  ngOnInit(): void {
    this.subscriptions.push(
      this.webrtcService.state$.subscribe(state => {
        if (state.cameraId === this.camera.id) {
          this.connectionState = state.connectionState;
          this.iceConnectionState = state.iceConnectionState;
          this.connectionStateChange.emit(state);

          if (state.connectionState === 'connected') {
            this.isLoading = false;
            this.hasError = false;
          } else if (state.connectionState === 'failed') {
            this.hasError = true;
            this.isLoading = false;
            this.errorMessage = 'Connection failed';
          }
        }
      })
    );

    this.subscriptions.push(
      this.webrtcService.stream$.subscribe(({ cameraId, stream }) => {
        if (cameraId === this.camera.id && this.videoElement) {
          this.videoElement.nativeElement.srcObject = stream;
          this.isLoading = false;
        }
      })
    );
  }

  ngAfterViewInit(): void {
    this.connect();
  }

  ngOnDestroy(): void {
    this.disconnect();
    this.subscriptions.forEach(sub => sub.unsubscribe());
  }

  connect(): void {
    this.isLoading = true;
    this.hasError = false;
    this.webrtcService.connectToCamera(this.camera.id);

    // Check if stream is already available
    const existingStream = this.webrtcService.getStream(this.camera.id);
    if (existingStream && this.videoElement) {
      this.videoElement.nativeElement.srcObject = existingStream;
      this.isLoading = false;
    }
  }

  disconnect(): void {
    this.webrtcService.disconnectFromCamera(this.camera.id);
    if (this.videoElement) {
      this.videoElement.nativeElement.srcObject = null;
    }
  }

  reconnect(): void {
    this.disconnect();
    setTimeout(() => this.connect(), 500);
  }

  get statusText(): string {
    if (this.hasError) return 'Erreur de connexion';
    if (this.isLoading) return 'Connexion en cours...';
    if (this.camera.status === 'recording') return 'EN DIRECT • ENREGISTREMENT';
    if (this.camera.status === 'streaming') return 'EN DIRECT';
    if (this.camera.status === 'online') return 'En ligne';
    return 'Hors ligne';
  }

  get statusClass(): string {
    if (this.hasError) return 'error';
    if (this.isLoading) return 'loading';
    if (this.camera.status === 'recording') return 'recording';
    if (this.camera.status === 'streaming') return 'live';
    if (this.camera.status === 'online') return 'online';
    return 'offline';
  }
}
