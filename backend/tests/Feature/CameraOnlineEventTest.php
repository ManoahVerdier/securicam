<?php

namespace Tests\Feature;

use App\Events\CameraOnline;
use App\Models\Camera;
use App\Models\User;
use Illuminate\Support\Facades\Event;
use Laravel\Sanctum\Sanctum;
use Tests\TestCase;

class CameraOnlineEventTest extends TestCase
{
    public function test_heartbeat_on_offline_camera_broadcasts_camera_online_event(): void
    {
        Event::fake([CameraOnline::class]);

        $user = User::factory()->create();
        $camera = Camera::factory()->offline()->create(['user_id' => $user->id]);

        Sanctum::actingAs($user);

        $response = $this->postJson("/api/cameras/{$camera->id}/heartbeat");

        $response->assertStatus(200);

        Event::assertDispatched(CameraOnline::class, function (CameraOnline $event) use ($camera) {
            return $event->cameraId === $camera->id;
        });

        $this->assertDatabaseHas('cameras', [
            'id' => $camera->id,
            'status' => Camera::STATUS_ONLINE,
        ]);

        $camera->refresh();
        $this->assertNotNull($camera->connected_at);
    }

    public function test_heartbeat_on_online_camera_does_not_broadcast(): void
    {
        Event::fake([CameraOnline::class]);

        $user = User::factory()->create();
        $camera = Camera::factory()->online()->create(['user_id' => $user->id]);

        Sanctum::actingAs($user);

        $response = $this->postJson("/api/cameras/{$camera->id}/heartbeat");

        $response->assertStatus(200);

        Event::assertNotDispatched(CameraOnline::class);

        $camera->refresh();
        $this->assertEquals(Camera::STATUS_ONLINE, $camera->status);
        $this->assertNotNull($camera->last_seen_at);
    }

    public function test_heartbeat_on_streaming_camera_does_not_broadcast(): void
    {
        Event::fake([CameraOnline::class]);

        $user = User::factory()->create();
        $camera = Camera::factory()->streaming()->create(['user_id' => $user->id]);

        Sanctum::actingAs($user);

        $response = $this->postJson("/api/cameras/{$camera->id}/heartbeat");

        $response->assertStatus(200);

        Event::assertNotDispatched(CameraOnline::class);

        $camera->refresh();
        $this->assertEquals(Camera::STATUS_STREAMING, $camera->status);
    }

    public function test_heartbeat_reconnection_sets_connected_at(): void
    {
        Event::fake([CameraOnline::class]);

        $user = User::factory()->create();
        $camera = Camera::factory()->offline()->create([
            'user_id' => $user->id,
            'connected_at' => null,
        ]);

        $this->assertNull($camera->connected_at);

        Sanctum::actingAs($user);

        $this->postJson("/api/cameras/{$camera->id}/heartbeat");

        $camera->refresh();
        $this->assertNotNull($camera->connected_at);
    }
}
