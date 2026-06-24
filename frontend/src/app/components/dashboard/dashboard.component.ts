import { Component, OnInit, OnDestroy, HostListener } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { Subscription } from 'rxjs';
import { Camera } from '../../models';
import { AuthService, CameraService, SignalingService } from '../../services';
import { WebrtcService } from '../../services';
import { CameraViewerComponent } from '../camera-viewer/camera-viewer.component';
import { CaptureControlsComponent } from '../capture-controls/capture-controls.component';
import { GalleryComponent } from '../gallery/gallery.component';

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [
    CommonModule,
    CameraViewerComponent,
    CaptureControlsComponent,
    GalleryComponent
  ],
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.scss'
})
export class DashboardComponent implements OnInit, OnDestroy {
  cameras: Camera[] = [];
  selectedCamera: Camera | null = null;
  viewMode: 'info' | 'stream' | 'gallery' = 'info';
  galleryFilter: 'photo' | 'video' | undefined;
  isLoading = true;
  error = '';
  private pollingHandle: ReturnType<typeof setInterval> | null = null;
  private joinedChannelCameraId: number | null = null;
  private onlineSubscription: Subscription | null = null;
  private offlineSubscription: Subscription | null = null;

  constructor(
    private authService: AuthService,
    private cameraService: CameraService,
    private signalingService: SignalingService,
    private webrtcService: WebrtcService,
    private router: Router
  ) {}

  ngOnInit(): void {
    this.loadCameras();
    this.signalingService.connect();
    this.pollingHandle = setInterval(() => this.loadCameras(), 5000);

    this.onlineSubscription = this.signalingService.cameraOnline$.subscribe(({ cameraId }) => {
      const idx = this.cameras.findIndex(c => c.id === cameraId);
      if (idx !== -1) {
        this.cameras[idx] = { ...this.cameras[idx], status: 'online' };
      }
      if (this.selectedCamera?.id === cameraId) {
        this.selectedCamera = { ...this.selectedCamera, status: 'online' };
      }
    });

    this.offlineSubscription = this.signalingService.cameraOffline$.subscribe(({ cameraId }) => {
      const idx = this.cameras.findIndex(c => c.id === cameraId);
      if (idx !== -1) {
        this.cameras[idx] = { ...this.cameras[idx], status: 'offline', connected_at: undefined };
      }
      if (this.selectedCamera?.id === cameraId) {
        this.selectedCamera = { ...this.selectedCamera, status: 'offline', connected_at: undefined };
      }
    });
  }

  ngOnDestroy(): void {
    if (this.pollingHandle) {
      clearInterval(this.pollingHandle);
      this.pollingHandle = null;
    }
    this.onlineSubscription?.unsubscribe();
    this.offlineSubscription?.unsubscribe();
    this.leaveCurrentChannel();
    this.signalingService.disconnect();
  }

  loadCameras(): void {
    const initial = this.cameras.length === 0;
    if (initial) {
      this.isLoading = true;
    }
    this.cameraService.getCameras().subscribe({
      next: (response) => {
        this.cameras = response.cameras;
        this.isLoading = false;

        if (this.selectedCamera) {
          const refreshed = this.cameras.find(c => c.id === this.selectedCamera!.id);
          if (refreshed) {
            this.selectedCamera = refreshed;
          }
        }
      },
      error: () => {
        if (initial) {
          this.error = 'Erreur lors du chargement des caméras';
        }
        this.isLoading = false;
      }
    });
  }

  selectCamera(camera: Camera): void {
    if (this.selectedCamera?.id !== camera.id) {
      this.leaveCurrentChannel();
    }
    this.selectedCamera = camera;
    this.viewMode = 'info';
    this.galleryFilter = undefined;
    // Join the camera private channel so the gallery receives `.capture.created`
    // events even when the user is not actively streaming.
    this.joinSelectedChannel();
  }

  private joinSelectedChannel(): void {
    if (!this.selectedCamera) {
      return;
    }
    if (this.joinedChannelCameraId === this.selectedCamera.id) {
      return;
    }
    this.signalingService.joinCameraChannel(this.selectedCamera.id);
    this.joinedChannelCameraId = this.selectedCamera.id;
  }

  private leaveCurrentChannel(): void {
    if (this.joinedChannelCameraId !== null) {
      this.signalingService.leaveChannel(this.joinedChannelCameraId);
      this.joinedChannelCameraId = null;
    }
  }

  startStreaming(): void {
    if (!this.selectedCamera) {
      return;
    }
    this.viewMode = 'stream';
  }

  openGallery(filter?: 'photo' | 'video'): void {
    if (!this.selectedCamera) {
      return;
    }
    this.galleryFilter = filter;
    this.viewMode = 'gallery';
  }

  backToInfo(): void {
    this.viewMode = 'info';
  }

  @HostListener('window:beforeunload')
  onBeforeUnload(): void {
    if (this.viewMode === 'stream' && this.selectedCamera) {
      this.webrtcService.requestStreamStop(this.selectedCamera.id);
    }
  }

  stopStreaming(): void {
    if (this.selectedCamera) {
      this.webrtcService.requestStreamStop(this.selectedCamera.id);
    }
    this.viewMode = 'info';
  }

  switchStreamCamera(camera: Camera): void {
    if (camera.id === this.selectedCamera?.id || !this.isReady(camera)) {
      return;
    }
    this.selectedCamera = null;
    setTimeout(() => {
      this.selectCamera(camera);
      this.viewMode = 'stream';
    }, 0);
  }

  isReady(camera: Camera): boolean {
    return camera.status !== 'offline';
  }

  onCameraStatusChanged(camera: Camera): void {
    // Update camera in list
    const index = this.cameras.findIndex(c => c.id === camera.id);
    if (index !== -1) {
      this.cameras[index] = camera;
    }
    if (this.selectedCamera?.id === camera.id) {
      this.selectedCamera = camera;
    }
  }

  onError(message: string): void {
    this.error = message;
    setTimeout(() => {
      this.error = '';
    }, 5000);
  }

  logout(): void {
    this.authService.logout().subscribe({
      next: () => {
        this.router.navigate(['/login']);
      },
      error: () => {
        this.router.navigate(['/login']);
      }
    });
  }

  get userName(): string {
    return this.authService.user()?.name || '';
  }
}
