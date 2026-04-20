<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Controller;
use App\Models\Camera;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;

class CameraController extends Controller
{
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
        ]);

        return response()->json([
            'message' => 'Camera status updated',
            'camera' => $camera,
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
