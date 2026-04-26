import { Component, Input, Output, EventEmitter } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Camera, CameraLens } from '../../models';
import { CaptureService } from '../../services';

@Component({
  selector: 'app-capture-controls',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './capture-controls.component.html',
  styleUrl: './capture-controls.component.scss'
})
export class CaptureControlsComponent {
  @Input({ required: true }) camera!: Camera;
  @Output() statusChanged = new EventEmitter<Camera>();
  @Output() error = new EventEmitter<string>();

  isCapturingPhoto = false;
  isTogglingRecord = false;
  isSwitchingCamera = false;
  switchingToLens: string | null = null;

  constructor(private captureService: CaptureService) {}

  get isRecording(): boolean {
    return this.camera.status === 'recording';
  }

  get isOnline(): boolean {
    return this.camera.status !== 'offline';
  }

  get availableLenses(): CameraLens[] {
    return this.camera.available_lenses || [];
  }

  get hasLensChoice(): boolean {
    return this.availableLenses.length > 1;
  }

  isActiveLens(lens: CameraLens): boolean {
    return this.camera.active_lens === lens.id;
  }

  capturePhoto(): void {
    if (!this.isOnline || this.isCapturingPhoto) return;

    this.isCapturingPhoto = true;
    this.captureService.triggerPhoto(this.camera.id).subscribe({
      next: () => {
        this.isCapturingPhoto = false;
      },
      error: (err) => {
        this.isCapturingPhoto = false;
        this.error.emit(err.error?.message || 'Erreur lors de la capture');
      }
    });
  }

  toggleRecording(): void {
    if (!this.isOnline || this.isTogglingRecord) return;

    this.isTogglingRecord = true;

    const action = this.isRecording
      ? this.captureService.stopVideoRecording(this.camera.id)
      : this.captureService.startVideoRecording(this.camera.id);

    action.subscribe({
      next: (response) => {
        this.isTogglingRecord = false;
        if (response.camera) {
          this.statusChanged.emit(response.camera);
        }
      },
      error: (err) => {
        this.isTogglingRecord = false;
        this.error.emit(err.error?.message || 'Erreur lors du changement d\'enregistrement');
      }
    });
  }

  /**
   * Toggle between front and back when no explicit lens is given.
   * If a [lensId] is provided (multi-back-camera phone), switch to that lens.
   */
  switchPhoneCamera(lensId?: string): void {
    if (!this.isOnline || this.isSwitchingCamera) return;

    this.isSwitchingCamera = true;
    this.switchingToLens = lensId ?? null;
    this.captureService.switchPhoneCamera(this.camera.id, lensId).subscribe({
      next: () => {
        this.isSwitchingCamera = false;
        this.switchingToLens = null;
      },
      error: (err) => {
        this.isSwitchingCamera = false;
        this.switchingToLens = null;
        this.error.emit(err.error?.message || 'Erreur lors du changement de caméra');
      }
    });
  }
}
