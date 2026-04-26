<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Controller;
use App\Models\Camera;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;

class CameraController extends Controller
{
    /**
     * Initialize camera status by device_id.
     */
    public function initStatus(Request $request): JsonResponse
    {
        $validated = $request->validate([
            'device_id' => ['required', 'string', 'max:255'],
            'status' => ['required', 'string', 'in:offline,online,streaming,recording'],
        ]);

        $camera = $request->user()
            ->cameras()
            ->where('device_id', $validated['device_id'])
            ->first();

        if (!$camera) {
            return response()->json([
                'message' => 'Camera not found',
            ], 404);
        }

        $camera->update([
            'status' => $validated['status'],
            'last_seen_at' => now(),
        ]);

        return response()->json([
            'message' => 'Camera status initialized',
            'camera' => $camera,
        ]);
    }

    /**
     * Display a listing of cameras for the authenticated user.
     */
    public function index(Request $request): JsonResponse
    {
        $cameras = $request->user()->cameras()
            ->orderBy('name')
            ->get();

        return response()->json([
            'cameras' => $cameras,
        ]);
    }

    /**
     * Store a newly created camera.
     */
    public function store(Request $request): JsonResponse
    {
        $validated = $request->validate([
            'name' => ['required', 'string', 'max:255'],
            'device_id' => ['required', 'string', 'max:255', 'unique:cameras'],
        ]);

        $camera = $request->user()->cameras()->create([
            'name' => $validated['name'],
            'device_id' => $validated['device_id'],
            'status' => Camera::STATUS_OFFLINE,
        ]);

        return response()->json([
            'message' => 'Camera registered successfully',
            'camera' => $camera,
        ], 201);
    }

    /**
     * Display the specified camera.
     */
    public function show(Request $request, Camera $camera): JsonResponse
    {
        $this->authorize('view', $camera);

        return response()->json([
            'camera' => $camera->load('captures'),
        ]);
    }

    /**
     * Update the specified camera.
     */
    public function update(Request $request, Camera $camera): JsonResponse
    {
        $this->authorize('update', $camera);

        $validated = $request->validate([
            'name' => ['sometimes', 'string', 'max:255'],
        ]);

        $camera->update($validated);

        return response()->json([
            'message' => 'Camera updated successfully',
            'camera' => $camera,
        ]);
    }

    /**
     * Update camera status (used by Android app).
     */
    public function updateStatus(Request $request, Camera $camera): JsonResponse
    {
        $this->authorize('update', $camera);

        $validated = $request->validate([
            'status' => ['required', 'string', 'in:offline,online,streaming,recording'],
        ]);

        $camera->update([
            'status' => $validated['status'],
            'last_seen_at' => now(),
            'last_ip' => $request->ip(),
        ]);

        return response()->json([
            'message' => 'Camera status updated',
            'camera' => $camera->fresh(),
        ]);
    }

    /**
     * Mark camera as connected (Android app just opened/registered).
     * Sets status=online, records connected_at and IP.
     */
    public function connect(Request $request, Camera $camera): JsonResponse
    {
        $this->authorize('update', $camera);

        $payload = $request->validate([
            'available_lenses' => ['sometimes', 'array'],
            'available_lenses.*.id' => ['required_with:available_lenses', 'string', 'max:64'],
            'available_lenses.*.facing' => ['required_with:available_lenses', 'string', 'in:front,back,external'],
            'available_lenses.*.label' => ['required_with:available_lenses', 'string', 'max:128'],
            'active_lens' => ['sometimes', 'nullable', 'string', 'max:64'],
        ]);

        $update = [
            'status' => Camera::STATUS_ONLINE,
            'last_seen_at' => now(),
            'connected_at' => now(),
            'last_ip' => $request->ip(),
        ];
        if (array_key_exists('available_lenses', $payload)) {
            $update['available_lenses'] = $payload['available_lenses'];
        }
        if (array_key_exists('active_lens', $payload)) {
            $update['active_lens'] = $payload['active_lens'];
        }
        $camera->update($update);

        return response()->json([
            'message' => 'Camera connected',
            'camera' => $camera->fresh(),
        ]);
    }

    /**
     * Heartbeat from Android: refresh last_seen_at + IP without changing connected_at.
     */
    public function heartbeat(Request $request, Camera $camera): JsonResponse
    {
        $this->authorize('update', $camera);

        $payload = $request->validate([
            'available_lenses' => ['sometimes', 'array'],
            'available_lenses.*.id' => ['required_with:available_lenses', 'string', 'max:64'],
            'available_lenses.*.facing' => ['required_with:available_lenses', 'string', 'in:front,back,external'],
            'available_lenses.*.label' => ['required_with:available_lenses', 'string', 'max:128'],
            'active_lens' => ['sometimes', 'nullable', 'string', 'max:64'],
        ]);

        $update = [
            'last_seen_at' => now(),
            'last_ip' => $request->ip(),
        ];
        if (array_key_exists('available_lenses', $payload)) {
            $update['available_lenses'] = $payload['available_lenses'];
        }
        if (array_key_exists('active_lens', $payload)) {
            $update['active_lens'] = $payload['active_lens'];
        }
        $camera->update($update);

        return response()->json([
            'camera' => $camera->fresh(),
        ]);
    }

    /**
     * Mark camera as disconnected (Android app stopping cleanly).
     */
    public function disconnect(Request $request, Camera $camera): JsonResponse
    {
        $this->authorize('update', $camera);

        $camera->update([
            'status' => Camera::STATUS_OFFLINE,
            'connected_at' => null,
            'last_seen_at' => now(),
        ]);

        return response()->json([
            'message' => 'Camera disconnected',
            'camera' => $camera->fresh(),
        ]);
    }

    /**
     * Remove the specified camera.
     */
    public function destroy(Request $request, Camera $camera): JsonResponse
    {
        $this->authorize('delete', $camera);

        $camera->delete();

        return response()->json([
            'message' => 'Camera deleted successfully',
        ]);
    }
}
