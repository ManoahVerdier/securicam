<?php

namespace Database\Factories;

use App\Models\Camera;
use App\Models\User;
use Illuminate\Database\Eloquent\Factories\Factory;
use Illuminate\Support\Str;

class CameraFactory extends Factory
{
    protected $model = Camera::class;

    public function definition(): array
    {
        return [
            'user_id' => User::factory(),
            'name' => fake()->words(2, true) . ' cam',
            'device_id' => Str::uuid()->toString(),
            'status' => Camera::STATUS_OFFLINE,
            'last_seen_at' => null,
            'last_ip' => null,
            'connected_at' => null,
            'available_lenses' => null,
            'active_lens' => null,
        ];
    }

    public function online(): static
    {
        return $this->state(['status' => Camera::STATUS_ONLINE]);
    }

    public function recording(): static
    {
        return $this->state(['status' => Camera::STATUS_RECORDING]);
    }
}
