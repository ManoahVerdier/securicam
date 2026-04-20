export interface Capture {
  id: number;
  camera_id: number;
  type: CaptureType;
  file_path: string;
  thumbnail_path?: string;
  duration?: number;
  file_size?: number;
  captured_at: string;
  created_at: string;
  updated_at: string;
  file_url?: string;
  thumbnail_url?: string;
}

export type CaptureType = 'photo' | 'video';

export interface CaptureListResponse {
  data: Capture[];
  current_page: number;
  last_page: number;
  per_page: number;
  total: number;
}

export interface CaptureResponse {
  capture: Capture;
  message?: string;
}

export interface TriggerCaptureRequest {
  camera_id: number;
}
