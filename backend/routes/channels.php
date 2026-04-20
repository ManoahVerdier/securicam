<?php

use App\Models\Camera;
use App\Models\User;
use Illuminate\Support\Facades\Broadcast;

/*
|--------------------------------------------------------------------------
| Broadcast Channels
|--------------------------------------------------------------------------
|
| Here you may register all of the event broadcasting channels that your
| application supports. The given channel authorization callbacks are
| used to check if an authenticated user can listen to the channel.
|
*/

Broadcast::channel('App.Models.User.{id}', function (User $user, int $id) {
    return $user->id === $id;
});

/**
 * Camera channel - allows the camera owner to subscribe.
 */
Broadcast::channel('camera.{cameraId}', function (User $user, int $cameraId) {
    $camera = Camera::find($cameraId);
    
    if (!$camera) {
        return false;
    }

    return $user->id === $camera->user_id;
});
