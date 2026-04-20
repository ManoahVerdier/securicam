import { Component, Input, Output, EventEmitter } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Camera } from '../../models';
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

  constructor(private captureService: CaptureService) {}

  get isRecording(): boolean {
    return this.camera.status === 'recording';
  }

  get isOnline(): boolean {
    return this.camera.status !== 'offline';
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
}
