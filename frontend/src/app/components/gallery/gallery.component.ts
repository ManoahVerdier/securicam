import { Component, Input, OnDestroy, OnInit, OnChanges, SimpleChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Subscription } from 'rxjs';
import { Camera, Capture, CaptureListResponse } from '../../models';
import { CaptureService, SignalingService } from '../../services';

@Component({
  selector: 'app-gallery',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './gallery.component.html',
  styleUrl: './gallery.component.scss'
})
export class GalleryComponent implements OnInit, OnChanges, OnDestroy {
  @Input({ required: true }) camera!: Camera;
  /** Optional filter — when set, only that type is fetched and shown. */
  @Input() filterType?: 'photo' | 'video';
  /** Optional title override (defaults to "Captures"). */
  @Input() title?: string;

  captures: Capture[] = [];
  isLoading = true;
  hasError = false;
  currentPage = 1;
  totalPages = 1;
  selectedCapture: Capture | null = null;

  private liveSub?: Subscription;

  constructor(
    private captureService: CaptureService,
    private signaling: SignalingService
  ) {}

  ngOnInit(): void {
    this.loadCaptures();
    this.subscribeToLiveCaptures();
  }

  ngOnChanges(changes: SimpleChanges): void {
    const cameraIdChanged = changes['camera'] && !changes['camera'].firstChange &&
      changes['camera'].previousValue?.id !== changes['camera'].currentValue?.id;
    const filterChanged = changes['filterType'] && !changes['filterType'].firstChange;
    if (cameraIdChanged || filterChanged) {
      this.loadCaptures();
    }
  }

  ngOnDestroy(): void {
    this.liveSub?.unsubscribe();
  }

  /** Push newly uploaded captures to the top of the grid in real time. */
  private subscribeToLiveCaptures(): void {
    this.liveSub = this.signaling.captureCreated$.subscribe(({ cameraId, capture }) => {
      if (cameraId !== this.camera.id) {
        return;
      }
      if (this.filterType && capture.type !== this.filterType) {
        return;
      }
      // Avoid duplicates if the API call already returned this capture.
      if (this.captures.some(c => c.id === capture.id)) {
        return;
      }
      this.captures = [capture, ...this.captures];
    });
  }

  loadCaptures(page: number = 1): void {
    this.isLoading = true;
    this.hasError = false;

    this.captureService.getCaptures(this.camera.id, page, 20, this.filterType).subscribe({
      next: (response: CaptureListResponse) => {
        this.captures = page === 1
          ? response.data
          : [...this.captures, ...response.data];
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
