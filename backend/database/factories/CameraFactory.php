<?php

namespace Database\Factories;

use App\Models\Camera;
use App\Models\User;
use Illuminate\Database\Eloquent\Factories\Factory;

/**
 * @extends \Illuminate\Database\Eloquent\Factories\Factory<\App\Models\Camera>
 */
class CameraFactory extends Factory
{
    protected $model = Camera::class;

    public function definition(): array
    {
        return [
            'user_id'          => User::factory(),
            'name'             => fake()->words(2, true),
            'device_id'        => fake()->unique()->uuid(),
            'status'           => Camera::STATUS_OFFLINE,
            'last_seen_at'     => null,
            'connected_at'     => null,
            'last_ip'          => null,
            'available_lenses' => null,
            'active_lens'      => null,
        ];
    }

    public function online(): static
    {
        return $this->state(fn (array $attributes) => [
            'status'       => Camera::STATUS_ONLINE,
            'connected_at' => now(),
            'last_seen_at' => now(),
        ]);
    }

    public function offline(): static
    {
        return $this->state(fn (array $attributes) => [
            'status'       => Camera::STATUS_OFFLINE,
            'connected_at' => null,
        ]);
    }

    public function streaming(): static
    {
        return $this->state(fn (array $attributes) => [
            'status'       => Camera::STATUS_STREAMING,
            'connected_at' => now(),
            'last_seen_at' => now(),
        ]);
    }

    public function recording(): static
    {
        return $this->state(fn (array $attributes) => [
            'status'       => Camera::STATUS_RECORDING,
            'connected_at' => now(),
            'last_seen_at' => now(),
        ]);
    }
}
