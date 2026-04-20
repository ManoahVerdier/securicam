export interface WebRtcOffer {
  camera_id: number;
  sdp: string;
  type: 'offer';
}

export interface WebRtcAnswer {
  camera_id: number;
  sdp: string;
  type: 'answer';
}

export interface WebRtcIceCandidate {
  camera_id: number;
  candidate: RTCIceCandidateInit;
}

export interface WebRtcSignalMessage {
  camera_id: number;
  sdp?: string;
  type?: string;
  candidate?: RTCIceCandidateInit;
}
