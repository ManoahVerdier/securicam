import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { Camera } from '../../models';
import { AuthService, CameraService, SignalingService } from '../../services';
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
  isLoading = true;
  error = '';

  constructor(
    private authService: AuthService,
    private cameraService: CameraService,
    private signalingService: SignalingService,
    private router: Router
  ) {}

  ngOnInit(): void {
    this.loadCameras();
    this.signalingService.connect();
  }

  ngOnDestroy(): void {
    this.signalingService.disconnect();
  }

  loadCameras(): void {
    this.isLoading = true;
    this.cameraService.getCameras().subscribe({
      next: (response) => {
        this.cameras = response.cameras;
        this.isLoading = false;

        // Auto-select first camera if available
        if (this.cameras.length > 0 && !this.selectedCamera) {
          this.selectCamera(this.cameras[0]);
        }
      },
      error: () => {
        this.error = 'Erreur lors du chargement des caméras';
        this.isLoading = false;
      }
    });
  }

  selectCamera(camera: Camera): void {
    this.selectedCamera = camera;
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
