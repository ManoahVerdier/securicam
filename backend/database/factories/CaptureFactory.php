<?php

namespace Database\Factories;

use App\Models\Camera;
use App\Models\Capture;
use Illuminate\Database\Eloquent\Factories\Factory;

class CaptureFactory extends Factory
{
    protected $model = Capture::class;

    public function definition(): array
    {
        $type = fake()->randomElement(['photo', 'video']);

        return [
            'camera_id' => Camera::factory(),
            'type' => $type,
            'file_path' => "captures/1/{$type}_" . fake()->unixTime() . ($type === 'photo' ? '.jpg' : '.mp4'),
            'thumbnail_path' => null,
            'duration' => $type === 'video' ? fake()->numberBetween(1, 300) : null,
            'file_size' => fake()->numberBetween(50000, 10000000),
            'captured_at' => fake()->dateTimeBetween('-30 days', 'now'),
        ];
    }

    public function photo(): static
    {
        return $this->state(['type' => 'photo', 'duration' => null]);
    }

    public function video(): static
    {
        return $this->state([
            'type' => 'video',
            'duration' => fake()->numberBetween(5, 300),
        ]);
    }
}
