<?php

namespace App\Policies;

use App\Models\Camera;
use App\Models\User;

class CameraPolicy
{
    /**
     * Determine whether the user can view any cameras.
     */
    public function viewAny(User $user): bool
    {
        return true;
    }

    /**
     * Determine whether the user can view the camera.
     */
    public function view(User $user, Camera $camera): bool
    {
        return $user->id === $camera->user_id;
    }

    /**
     * Determine whether the user can create cameras.
     */
    public function create(User $user): bool
    {
        return true;
    }

    /**
     * Determine whether the user can update the camera.
     */
    public function update(User $user, Camera $camera): bool
    {
        return $user->id === $camera->user_id;
    }

    /**
     * Determine whether the user can delete the camera.
     */
    public function delete(User $user, Camera $camera): bool
    {
        return $user->id === $camera->user_id;
    }
}
