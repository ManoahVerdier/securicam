import { Component, Input, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Camera, Capture, CaptureListResponse } from '../../models';
import { CaptureService } from '../../services';

@Component({
  selector: 'app-gallery',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './gallery.component.html',
  styleUrl: './gallery.component.scss'
})
export class GalleryComponent implements OnInit {
  @Input({ required: true }) camera!: Camera;

  captures: Capture[] = [];
  isLoading = true;
  hasError = false;
  currentPage = 1;
  totalPages = 1;
  selectedCapture: Capture | null = null;

  constructor(private captureService: CaptureService) {}

  ngOnInit(): void {
    this.loadCaptures();
  }

  loadCaptures(page: number = 1): void {
    this.isLoading = true;
    this.hasError = false;

    this.captureService.getCaptures(this.camera.id, page).subscribe({
      next: (response: CaptureListResponse) => {
        this.captures = response.data;
        this.currentPage = response.current_page;
        this.totalPages = response.last_page;
        this.isLoading = false;
      },
      error: () => {
        this.hasError = true;
        this.isLoading = false;
      }
    });
  }

  loadMore(): void {
    if (this.currentPage < this.totalPages) {
      this.loadCaptures(this.currentPage + 1);
    }
  }

  openCapture(capture: Capture): void {
    this.selectedCapture = capture;
  }

  closeViewer(): void {
    this.selectedCapture = null;
  }

  deleteCapture(capture: Capture, event: Event): void {
    event.stopPropagation();

    if (!confirm('Êtes-vous sûr de vouloir supprimer cette capture ?')) {
      return;
    }

    this.captureService.deleteCapture(this.camera.id, capture.id).subscribe({
      next: () => {
        this.captures = this.captures.filter(c => c.id !== capture.id);
        if (this.selectedCapture?.id === capture.id) {
          this.selectedCapture = null;
        }
      },
      error: () => {
        alert('Erreur lors de la suppression');
      }
    });
  }

  formatDate(dateString: string): string {
    const date = new Date(dateString);
    return date.toLocaleDateString('fr-FR', {
      day: '2-digit',
      month: '2-digit',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit'
    });
  }

  formatDuration(seconds: number): string {
    const mins = Math.floor(seconds / 60);
    const secs = seconds % 60;
    return `${mins}:${secs.toString().padStart(2, '0')}`;
  }

  formatFileSize(bytes: number): string {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
  }
}
