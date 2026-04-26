export interface CameraLens {
  id: string;
  facing: 'front' | 'back' | 'external';
  label: string;
}

export interface Camera {
  id: number;
  user_id: number;
  name: string;
  device_id: string;
  status: CameraStatus;
  last_seen_at?: string;
  last_ip?: string;
  connected_at?: string;
  available_lenses?: CameraLens[] | null;
  active_lens?: string | null;
  created_at: string;
  updated_at: string;
}

export type CameraStatus = 'offline' | 'online' | 'streaming' | 'recording';

export interface CameraListResponse {
  cameras: Camera[];
}

export interface CameraResponse {
  camera: Camera;
  message?: string;
}

export interface CreateCameraRequest {
  name: string;
  device_id: string;
}

export interface UpdateCameraRequest {
  name?: string;
}

export interface UpdateCameraStatusRequest {
  status: CameraStatus;
}
