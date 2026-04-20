import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';
import {
  Camera,
  CameraListResponse,
  CameraResponse,
  CreateCameraRequest,
  UpdateCameraRequest,
  UpdateCameraStatusRequest
} from '../models';

@Injectable({
  providedIn: 'root'
})
export class CameraService {
  private readonly apiUrl = `${environment.apiUrl}/cameras`;

  constructor(private http: HttpClient) {}

  getCameras(): Observable<CameraListResponse> {
    return this.http.get<CameraListResponse>(this.apiUrl);
  }

  getCamera(id: number): Observable<CameraResponse> {
    return this.http.get<CameraResponse>(`${this.apiUrl}/${id}`);
  }

  createCamera(data: CreateCameraRequest): Observable<CameraResponse> {
    return this.http.post<CameraResponse>(this.apiUrl, data);
  }

  updateCamera(id: number, data: UpdateCameraRequest): Observable<CameraResponse> {
    return this.http.put<CameraResponse>(`${this.apiUrl}/${id}`, data);
  }

  updateCameraStatus(id: number, data: UpdateCameraStatusRequest): Observable<CameraResponse> {
    return this.http.patch<CameraResponse>(`${this.apiUrl}/${id}/status`, data);
  }

  deleteCamera(id: number): Observable<{ message: string }> {
    return this.http.delete<{ message: string }>(`${this.apiUrl}/${id}`);
  }
}
