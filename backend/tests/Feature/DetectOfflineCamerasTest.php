<?php

namespace Tests\Feature;

use App\Events\CameraOffline;
use App\Models\Camera;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Carbon;
use Illuminate\Support\Facades\Event;
use Tests\TestCase;

class DetectOfflineCamerasTest extends TestCase
{
    use RefreshDatabase;

    public function test_marks_stale_online_camera_offline_and_broadcasts(): void
    {
        Event::fake([CameraOffline::class]);

        $camera = Camera::factory()->online()->create([
            'last_seen_at' => Carbon::now()->subMinutes(5),
            'connected_at' => Carbon::now()->subMinutes(10),
        ]);

        $this->artisan('cameras:detect-offline')->assertExitCode(0);

        $camera->refresh();
        $this->assertEquals(Camera::STATUS_OFFLINE, $camera->status);
        $this->assertNull($camera->connected_at);

        Event::assertDispatched(CameraOffline::class, function (CameraOffline $event) use ($camera) {
            return $event->cameraId === $camera->id;
        });
    }

    public function test_does_not_touch_camera_with_recent_heartbeat(): void
    {
        Event::fake([CameraOffline::class]);

        $camera = Camera::factory()->online()->create([
            'last_seen_at' => Carbon::now()->subSeconds(30),
        ]);

        $this->artisan('cameras:detect-offline')->assertExitCode(0);

        $camera->refresh();
        $this->assertEquals(Camera::STATUS_ONLINE, $camera->status);

        Event::assertNotDispatched(CameraOffline::class);
    }

    public function test_marks_camera_with_null_last_seen_offline(): void
    {
        Event::fake([CameraOffline::class]);

        $camera = Camera::factory()->create([
            'status'       => Camera::STATUS_STREAMING,
            'last_seen_at' => null,
        ]);

        $this->artisan('cameras:detect-offline')->assertExitCode(0);

        $camera->refresh();
        $this->assertEquals(Camera::STATUS_OFFLINE, $camera->status);

        Event::assertDispatched(CameraOffline::class, function (CameraOffline $event) use ($camera) {
            return $event->cameraId === $camera->id;
        });
    }

    public function test_is_idempotent(): void
    {
        Event::fake([CameraOffline::class]);

        Camera::factory()->create([
            'status'       => Camera::STATUS_OFFLINE,
            'last_seen_at' => Carbon::now()->subMinutes(5),
        ]);

        $this->artisan('cameras:detect-offline')->assertExitCode(0);
        $this->artisan('cameras:detect-offline')->assertExitCode(0);

        Event::assertNotDispatched(CameraOffline::class);
    }

    public function test_respects_custom_threshold(): void
    {
        Event::fake([CameraOffline::class]);

        config(['cameras.offline_threshold_seconds' => 300]);

        $camera = Camera::factory()->online()->create([
            'last_seen_at' => Carbon::now()->subSeconds(200),
        ]);

        $this->artisan('cameras:detect-offline')->assertExitCode(0);

        $camera->refresh();
        $this->assertEquals(Camera::STATUS_ONLINE, $camera->status);

        Event::assertNotDispatched(CameraOffline::class);
    }
}
