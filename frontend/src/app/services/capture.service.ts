import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';
import {
  Capture,
  CaptureListResponse,
  CaptureResponse,
  TriggerCaptureRequest
} from '../models';

@Injectable({
  providedIn: 'root'
})
export class CaptureService {
  private readonly apiUrl = environment.apiUrl;

  constructor(private http: HttpClient) {}

  getCaptures(
    cameraId: number,
    page: number = 1,
    perPage: number = 20,
    type?: 'photo' | 'video'
  ): Observable<CaptureListResponse> {
    const params: Record<string, string> = {
      page: page.toString(),
      per_page: perPage.toString()
    };
    if (type) {
      params['type'] = type;
    }
    return this.http.get<CaptureListResponse>(
      `${this.apiUrl}/cameras/${cameraId}/captures`,
      { params }
    );
  }

  getCapture(cameraId: number, captureId: number): Observable<CaptureResponse> {
    return this.http.get<CaptureResponse>(
      `${this.apiUrl}/cameras/${cameraId}/captures/${captureId}`
    );
  }

  deleteCapture(cameraId: number, captureId: number): Observable<{ message: string }> {
    return this.http.delete<{ message: string }>(
      `${this.apiUrl}/cameras/${cameraId}/captures/${captureId}`
    );
  }

  triggerPhoto(cameraId: number): Observable<{ message: string }> {
    const data: TriggerCaptureRequest = { camera_id: cameraId };
    return this.http.post<{ message: string }>(`${this.apiUrl}/captures/photo`, data);
  }

  startVideoRecording(cameraId: number): Observable<{ message: string; camera: any }> {
    const data: TriggerCaptureRequest = { camera_id: cameraId };
    return this.http.post<{ message: string; camera: any }>(`${this.apiUrl}/captures/video/start`, data);
  }

  stopVideoRecording(cameraId: number): Observable<{ message: string; camera: any }> {
    const data: TriggerCaptureRequest = { camera_id: cameraId };
    return this.http.post<{ message: string; camera: any }>(`${this.apiUrl}/captures/video/stop`, data);
  }
}
