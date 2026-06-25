<?php

use App\Http\Controllers\Api\AuthController;
use App\Http\Controllers\Api\CameraController;
use App\Http\Controllers\Api\CaptureController;
use App\Http\Controllers\Api\WebRtcController;
use Illuminate\Support\Facades\Route;

/*
|--------------------------------------------------------------------------
| API Routes
|--------------------------------------------------------------------------
|
| Here is where you can register API routes for your application. These
| routes are loaded by the RouteServiceProvider and all of them will
| be assigned to the "api" middleware group.
|
*/

// Health check (unauthenticated — used by monitoring and deploy scripts)
Route::get('/health', function () {
    return response()->json(['status' => 'ok', 'timestamp' => now()->toIso8601String()]);
});

// Public routes — rate-limited to blunt brute-force and registration abuse
Route::middleware('throttle:10,1')->group(function () {
    Route::post('/register', [AuthController::class, 'register']);
    Route::post('/login', [AuthController::class, 'login']);
});

// Protected routes
Route::middleware('auth:sanctum')->group(function () {
    // Auth
    Route::get('/user', [AuthController::class, 'user']);
    Route::post('/logout', [AuthController::class, 'logout']);
    Route::post('/revoke-tokens', [AuthController::class, 'revokeAllTokens']);

    // Cameras
    Route::apiResource('cameras', CameraController::class);
    Route::post('/cameras/init-status', [CameraController::class, 'initStatus']);
    Route::patch('/cameras/{camera}/status', [CameraController::class, 'updateStatus']);
    Route::post('/cameras/{camera}/connect', [CameraController::class, 'connect']);
    Route::post('/cameras/{camera}/heartbeat', [CameraController::class, 'heartbeat']);
    Route::post('/cameras/{camera}/disconnect', [CameraController::class, 'disconnect']);

    // WebRTC signaling
    Route::prefix('webrtc')->group(function () {
        Route::post('/start', [WebRtcController::class, 'start']);
        Route::post('/stop', [WebRtcController::class, 'stop']);
        Route::post('/offer', [WebRtcController::class, 'offer']);
        Route::post('/answer', [WebRtcController::class, 'answer']);
        Route::post('/ice-candidate', [WebRtcController::class, 'iceCandidate']);
    });

    // Captures
    Route::prefix('captures')->group(function () {
        Route::post('/photo', [CaptureController::class, 'triggerPhoto']);
        Route::post('/photo/hd', [CaptureController::class, 'triggerPhotoHd']);
        Route::post('/burst', [CaptureController::class, 'triggerBurstClassic']);
        Route::post('/continuous/start-classic', [CaptureController::class, 'triggerContinuousStartClassic']);
        Route::post('/continuous/start-hd', [CaptureController::class, 'triggerContinuousStartHd']);
        Route::post('/continuous/stop', [CaptureController::class, 'triggerContinuousStop']);
        Route::post('/video/start', [CaptureController::class, 'startVideo']);
        Route::post('/video/stop', [CaptureController::class, 'stopVideo']);
        Route::post('/upload', [CaptureController::class, 'store']);
        Route::post('/switch-camera', [CaptureController::class, 'switchCamera']);
    });

    // Camera captures
    Route::get('/cameras/{camera}/captures', [CaptureController::class, 'index']);
    Route::get('/cameras/{camera}/captures/{capture}', [CaptureController::class, 'show']);
    Route::delete('/cameras/{camera}/captures/{capture}', [CaptureController::class, 'destroy']);
});
