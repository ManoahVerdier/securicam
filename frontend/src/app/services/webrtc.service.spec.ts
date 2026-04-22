import { TestBed, fakeAsync, flushMicrotasks, tick } from '@angular/core/testing';
import { Subject } from 'rxjs';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { WebrtcService } from './webrtc.service';
import { SignalingService } from './signaling.service';
import { environment } from '../../environments/environment';

class MockSignalingService {
  offers$ = new Subject<any>();
  iceCandidates$ = new Subject<any>();
  joinCameraChannel = jasmine.createSpy('joinCameraChannel');
  leaveChannel = jasmine.createSpy('leaveChannel');
}

class MockRTCPeerConnection {
  connectionState: RTCPeerConnectionState = 'new';
  iceConnectionState: RTCIceConnectionState = 'new';
  onicecandidate: ((event: RTCPeerConnectionIceEvent) => void) | null = null;
  ontrack: ((event: RTCTrackEvent) => void) | null = null;
  onconnectionstatechange: (() => void) | null = null;
  oniceconnectionstatechange: (() => void) | null = null;

  close(): void {}
  restartIce(): void {}
}

describe('WebrtcService', () => {
  let service: WebrtcService;
  let httpMock: HttpTestingController;
  let signalingService: MockSignalingService;
  let originalPeerConnection: any;

  beforeEach(() => {
    originalPeerConnection = (globalThis as any).RTCPeerConnection;
    (globalThis as any).RTCPeerConnection = MockRTCPeerConnection as any;

    signalingService = new MockSignalingService();

    TestBed.configureTestingModule({
      providers: [
        WebrtcService,
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: SignalingService, useValue: signalingService }
      ]
    });

    service = TestBed.inject(WebrtcService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
    (globalThis as any).RTCPeerConnection = originalPeerConnection;
  });

  it('waits for camera readiness before creating peer connection', fakeAsync(() => {
    let completed = false;
    service.connectToCamera(1).then(() => {
      completed = true;
    });

    const startRequest = httpMock.expectOne(`${environment.apiUrl}/webrtc/start`);
    expect(startRequest.request.method).toBe('POST');
    startRequest.flush({});
    flushMicrotasks();

    const firstPoll = httpMock.expectOne(`${environment.apiUrl}/cameras/1`);
    firstPoll.flush({ camera: { status: 'offline' } });

    tick(2000);
    flushMicrotasks();

    const secondPoll = httpMock.expectOne(`${environment.apiUrl}/cameras/1`);
    secondPoll.flush({ camera: { status: 'streaming' } });
    flushMicrotasks();

    expect(completed).toBeTrue();
    expect(signalingService.joinCameraChannel).toHaveBeenCalledWith(1);
    expect((service as any).peerConnections.has(1)).toBeTrue();
  }));

  it('continues polling camera status when start request returns 422', fakeAsync(() => {
    let completed = false;
    service.connectToCamera(2).then(() => {
      completed = true;
    });

    const startRequest = httpMock.expectOne(`${environment.apiUrl}/webrtc/start`);
    startRequest.flush({ message: 'Camera is offline' }, { status: 422, statusText: 'Unprocessable Content' });
    flushMicrotasks();

    const pollRequest = httpMock.expectOne(`${environment.apiUrl}/cameras/2`);
    pollRequest.flush({ camera: { status: 'online' } });
    flushMicrotasks();

    expect(completed).toBeTrue();
    expect((service as any).peerConnections.has(2)).toBeTrue();
  }));
});
