<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Controller;
use App\Models\Camera;
use App\Events\WebRtcOffer;
use App\Events\WebRtcAnswer;
use App\Events\WebRtcIceCandidate;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;

class WebRtcController extends Controller
{
    /**
     * Receive WebRTC offer from camera (Android app).
     */
    public function offer(Request $request): JsonResponse
    {
        $validated = $request->validate([
            'camera_id' => ['required', 'exists:cameras,id'],
            'sdp' => ['required', 'string'],
            'type' => ['required', 'string', 'in:offer'],
        ]);

        $camera = Camera::findOrFail($validated['camera_id']);
        $this->authorize('update', $camera);

        // Broadcast the offer to the camera's channel for viewers
        broadcast(new WebRtcOffer(
            cameraId: $camera->id,
            sdp: $validated['sdp'],
            type: $validated['type']
        ))->toOthers();

        return response()->json([
            'message' => 'Offer broadcasted successfully',
        ]);
    }

    /**
     * Receive WebRTC answer from viewer (Angular app).
     */
    public function answer(Request $request): JsonResponse
    {
        $validated = $request->validate([
            'camera_id' => ['required', 'exists:cameras,id'],
            'sdp' => ['required', 'string'],
            'type' => ['required', 'string', 'in:answer'],
        ]);

        $camera = Camera::findOrFail($validated['camera_id']);
        $this->authorize('view', $camera);

        // Broadcast the answer to the camera's channel
        broadcast(new WebRtcAnswer(
            cameraId: $camera->id,
            sdp: $validated['sdp'],
            type: $validated['type']
        ))->toOthers();

        return response()->json([
            'message' => 'Answer broadcasted successfully',
        ]);
    }

    /**
     * Exchange ICE candidates between peers.
     */
    public function iceCandidate(Request $request): JsonResponse
    {
        $validated = $request->validate([
            'camera_id' => ['required', 'exists:cameras,id'],
            'candidate' => ['required', 'array'],
            'candidate.candidate' => ['required', 'string'],
            'candidate.sdpMid' => ['nullable', 'string'],
            'candidate.sdpMLineIndex' => ['nullable', 'integer'],
        ]);

        $camera = Camera::findOrFail($validated['camera_id']);
        $this->authorize('view', $camera);

        // Broadcast ICE candidate to the camera's channel
        broadcast(new WebRtcIceCandidate(
            cameraId: $camera->id,
            candidate: $validated['candidate']
        ))->toOthers();

        return response()->json([
            'message' => 'ICE candidate broadcasted successfully',
        ]);
    }
}
