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
  isCapturingPhotoHd = false;
  isBursting = false;
  isContinuousClassicActive = false;
  isContinuousHdActive = false;
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

  get isContinuousActive(): boolean {
    return this.isContinuousClassicActive || this.isContinuousHdActive;
  }

  isActiveLens(lens: CameraLens): boolean {
    return this.camera.active_lens === lens.id;
  }

  capturePhoto(): void {
    if (!this.isOnline || this.isCapturingPhoto) return;
    this.isCapturingPhoto = true;
    this.captureService.triggerPhoto(this.camera.id).subscribe({
      next: () => { this.isCapturingPhoto = false; },
      error: (err) => {
        this.isCapturingPhoto = false;
        this.error.emit(err.error?.message || 'Erreur lors de la capture');
      }
    });
  }

  capturePhotoHd(): void {
    if (!this.isOnline || this.isCapturingPhotoHd || this.isRecording) return;
    this.isCapturingPhotoHd = true;
    this.captureService.triggerPhotoHd(this.camera.id).subscribe({
      next: () => { this.isCapturingPhotoHd = false; },
      error: (err) => {
        this.isCapturingPhotoHd = false;
        this.error.emit(err.error?.message || 'Erreur lors de la capture HD');
      }
    });
  }

  triggerBurst(): void {
    if (!this.isOnline || this.isBursting) return;
    this.isBursting = true;
    this.captureService.triggerBurst(this.camera.id, 100).subscribe({
      next: () => {
        // Burst runs ~3s on the phone; reset flag after estimated duration
        setTimeout(() => { this.isBursting = false; }, 5000);
      },
      error: (err) => {
        this.isBursting = false;
        this.error.emit(err.error?.message || 'Erreur lors de la rafale');
      }
    });
  }

  toggleContinuousClassic(): void {
    if (!this.isOnline) return;
    if (this.isContinuousClassicActive) {
      this.captureService.stopContinuous(this.camera.id).subscribe({
        next: () => { this.isContinuousClassicActive = false; },
        error: () => { this.isContinuousClassicActive = false; }
      });
    } else {
      if (this.isContinuousHdActive) this.stopContinuousInternal();
      this.captureService.startContinuousClassic(this.camera.id).subscribe({
        next: () => { this.isContinuousClassicActive = true; },
        error: (err) => { this.error.emit(err.error?.message || 'Erreur continu classique'); }
      });
    }
  }

  toggleContinuousHd(): void {
    if (!this.isOnline) return;
    if (this.isContinuousHdActive) {
      this.captureService.stopContinuous(this.camera.id).subscribe({
        next: () => { this.isContinuousHdActive = false; },
        error: () => { this.isContinuousHdActive = false; }
      });
    } else {
      if (this.isContinuousClassicActive) this.stopContinuousInternal();
      this.captureService.startContinuousHd(this.camera.id).subscribe({
        next: () => { this.isContinuousHdActive = true; },
        error: (err) => { this.error.emit(err.error?.message || 'Erreur continu HD'); }
      });
    }
  }

  private stopContinuousInternal(): void {
    this.captureService.stopContinuous(this.camera.id).subscribe();
    this.isContinuousClassicActive = false;
    this.isContinuousHdActive = false;
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
        if (response.camera) this.statusChanged.emit(response.camera);
      },
      error: (err) => {
        this.isTogglingRecord = false;
        this.error.emit(err.error?.message || 'Erreur enregistrement');
      }
    });
  }

  switchPhoneCamera(lensId?: string): void {
    if (!this.isOnline || this.isSwitchingCamera) return;
    this.isSwitchingCamera = true;
    this.switchingToLens = lensId ?? null;
    this.captureService.switchPhoneCamera(this.camera.id, lensId).subscribe({
      next: () => { this.isSwitchingCamera = false; this.switchingToLens = null; },
      error: (err) => {
        this.isSwitchingCamera = false;
        this.switchingToLens = null;
        this.error.emit(err.error?.message || 'Erreur changement caméra');
      }
    });
  }
}
