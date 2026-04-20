<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Controller;
use App\Models\Camera;
use App\Models\Capture;
use App\Events\CapturePhoto;
use App\Events\VideoRecordingStart;
use App\Events\VideoRecordingStop;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Storage;

class CaptureController extends Controller
{
    /**
     * Display a listing of captures for a camera.
     */
    public function index(Request $request, Camera $camera): JsonResponse
    {
        $this->authorize('view', $camera);

        $captures = $camera->captures()
            ->orderByDesc('captured_at')
            ->paginate($request->input('per_page', 20));

        return response()->json($captures);
    }

    /**
     * Trigger a photo capture on the camera.
     */
    public function triggerPhoto(Request $request): JsonResponse
    {
        $validated = $request->validate([
            'camera_id' => ['required', 'exists:cameras,id'],
        ]);

        $camera = Camera::findOrFail($validated['camera_id']);
        $this->authorize('update', $camera);

        if (!$camera->isOnline()) {
            return response()->json([
                'message' => 'Camera is offline',
            ], 422);
        }

        // Broadcast event to trigger photo capture on Android app
        broadcast(new CapturePhoto($camera->id))->toOthers();

        return response()->json([
            'message' => 'Photo capture triggered',
        ]);
    }

    /**
     * Start video recording on the camera.
     */
    public function startVideo(Request $request): JsonResponse
    {
        $validated = $request->validate([
            'camera_id' => ['required', 'exists:cameras,id'],
        ]);

        $camera = Camera::findOrFail($validated['camera_id']);
        $this->authorize('update', $camera);

        if (!$camera->isOnline()) {
            return response()->json([
                'message' => 'Camera is offline',
            ], 422);
        }

        if ($camera->isRecording()) {
            return response()->json([
                'message' => 'Camera is already recording',
            ], 422);
        }

        // Update camera status
        $camera->update(['status' => Camera::STATUS_RECORDING]);

        // Broadcast event to start recording on Android app
        broadcast(new VideoRecordingStart($camera->id))->toOthers();

        return response()->json([
            'message' => 'Video recording started',
            'camera' => $camera,
        ]);
    }

    /**
     * Stop video recording on the camera.
     */
    public function stopVideo(Request $request): JsonResponse
    {
        $validated = $request->validate([
            'camera_id' => ['required', 'exists:cameras,id'],
        ]);

        $camera = Camera::findOrFail($validated['camera_id']);
        $this->authorize('update', $camera);

        if (!$camera->isRecording()) {
            return response()->json([
                'message' => 'Camera is not recording',
            ], 422);
        }

        // Update camera status
        $camera->update(['status' => Camera::STATUS_STREAMING]);

        // Broadcast event to stop recording on Android app
        broadcast(new VideoRecordingStop($camera->id))->toOthers();

        return response()->json([
            'message' => 'Video recording stopped',
            'camera' => $camera,
        ]);
    }

    /**
     * Store a capture uploaded from the camera.
     */
    public function store(Request $request): JsonResponse
    {
        $validated = $request->validate([
            'camera_id' => ['required', 'exists:cameras,id'],
            'type' => ['required', 'string', 'in:photo,video'],
            'file' => ['required', 'file', 'max:102400'], // 100MB max
            'thumbnail' => ['sometimes', 'file', 'image', 'max:1024'], // 1MB max for thumbnail
            'duration' => ['sometimes', 'integer', 'min:0'],
            'captured_at' => ['sometimes', 'date'],
        ]);

        $camera = Camera::findOrFail($validated['camera_id']);
        $this->authorize('update', $camera);

        // Store the file
        $filePath = $request->file('file')->store(
            "captures/{$camera->id}",
            'public'
        );

        // Store thumbnail if provided
        $thumbnailPath = null;
        if ($request->hasFile('thumbnail')) {
            $thumbnailPath = $request->file('thumbnail')->store(
                "captures/{$camera->id}/thumbnails",
                'public'
            );
        }

        $capture = $camera->captures()->create([
            'type' => $validated['type'],
            'file_path' => $filePath,
            'thumbnail_path' => $thumbnailPath,
            'duration' => $validated['duration'] ?? null,
            'file_size' => $request->file('file')->getSize(),
            'captured_at' => $validated['captured_at'] ?? now(),
        ]);

        return response()->json([
            'message' => 'Capture stored successfully',
            'capture' => $capture,
        ], 201);
    }

    /**
     * Display the specified capture.
     */
    public function show(Camera $camera, Capture $capture): JsonResponse
    {
        $this->authorize('view', $camera);

        if ($capture->camera_id !== $camera->id) {
            abort(404);
        }

        return response()->json([
            'capture' => $capture,
        ]);
    }

    /**
     * Remove the specified capture.
     */
    public function destroy(Camera $camera, Capture $capture): JsonResponse
    {
        $this->authorize('update', $camera);

        if ($capture->camera_id !== $camera->id) {
            abort(404);
        }

        // Delete files from storage
        if ($capture->file_path) {
            Storage::disk('public')->delete($capture->file_path);
        }
        if ($capture->thumbnail_path) {
            Storage::disk('public')->delete($capture->thumbnail_path);
        }

        $capture->delete();

        return response()->json([
            'message' => 'Capture deleted successfully',
        ]);
    }
}
