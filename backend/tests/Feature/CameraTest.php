<?php

namespace Tests\Feature;

use App\Models\Camera;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Laravel\Sanctum\Sanctum;
use Tests\TestCase;

class CameraTest extends TestCase
{
    use RefreshDatabase;

    public function test_user_can_list_own_cameras(): void
    {
        $user = User::factory()->create();
        Camera::factory()->count(3)->create(['user_id' => $user->id]);
        Camera::factory()->create(); // belongs to another user

        Sanctum::actingAs($user);

        $this->getJson('/api/cameras')
            ->assertStatus(200)
            ->assertJsonCount(3, 'cameras');
    }

    public function test_user_can_register_a_camera(): void
    {
        $user = User::factory()->create();
        Sanctum::actingAs($user);

        $this->postJson('/api/cameras', [
            'name' => 'Front Door',
            'device_id' => 'device-abc-123',
        ])->assertStatus(201)
            ->assertJsonPath('camera.name', 'Front Door')
            ->assertJsonPath('camera.user_id', $user->id);
    }

    public function test_register_camera_rejects_duplicate_device_id(): void
    {
        Camera::factory()->create(['device_id' => 'duplicate-id']);

        $user = User::factory()->create();
        Sanctum::actingAs($user);

        $this->postJson('/api/cameras', [
            'name' => 'My Camera',
            'device_id' => 'duplicate-id',
        ])->assertStatus(422);
    }

    public function test_user_can_view_own_camera(): void
    {
        $user = User::factory()->create();
        $camera = Camera::factory()->create(['user_id' => $user->id]);
        Sanctum::actingAs($user);

        $this->getJson("/api/cameras/{$camera->id}")
            ->assertStatus(200)
            ->assertJsonPath('camera.id', $camera->id);
    }

    public function test_user_cannot_view_another_users_camera(): void
    {
        $owner = User::factory()->create();
        $camera = Camera::factory()->create(['user_id' => $owner->id]);

        $attacker = User::factory()->create();
        Sanctum::actingAs($attacker);

        $this->getJson("/api/cameras/{$camera->id}")->assertStatus(403);
    }

    public function test_user_can_update_own_camera_name(): void
    {
        $user = User::factory()->create();
        $camera = Camera::factory()->create(['user_id' => $user->id]);
        Sanctum::actingAs($user);

        $this->patchJson("/api/cameras/{$camera->id}", ['name' => 'Back Door'])
            ->assertStatus(200)
            ->assertJsonPath('camera.name', 'Back Door');
    }

    public function test_user_cannot_update_another_users_camera(): void
    {
        $owner = User::factory()->create();
        $camera = Camera::factory()->create(['user_id' => $owner->id]);

        $attacker = User::factory()->create();
        Sanctum::actingAs($attacker);

        $this->patchJson("/api/cameras/{$camera->id}", ['name' => 'Hijacked'])
            ->assertStatus(403);
    }

    public function test_user_can_delete_own_camera(): void
    {
        $user = User::factory()->create();
        $camera = Camera::factory()->create(['user_id' => $user->id]);
        Sanctum::actingAs($user);

        $this->deleteJson("/api/cameras/{$camera->id}")->assertStatus(200);
        $this->assertDatabaseMissing('cameras', ['id' => $camera->id]);
    }

    public function test_user_cannot_delete_another_users_camera(): void
    {
        $owner = User::factory()->create();
        $camera = Camera::factory()->create(['user_id' => $owner->id]);

        $attacker = User::factory()->create();
        Sanctum::actingAs($attacker);

        $this->deleteJson("/api/cameras/{$camera->id}")->assertStatus(403);
        $this->assertDatabaseHas('cameras', ['id' => $camera->id]);
    }

    public function test_heartbeat_updates_last_seen_at(): void
    {
        $user = User::factory()->create();
        $camera = Camera::factory()->online()->create(['user_id' => $user->id]);
        Sanctum::actingAs($user);

        $this->postJson("/api/cameras/{$camera->id}/heartbeat")
            ->assertStatus(200);

        $this->assertNotNull($camera->fresh()->last_seen_at);
    }

    public function test_disconnect_sets_camera_offline(): void
    {
        $user = User::factory()->create();
        $camera = Camera::factory()->online()->create(['user_id' => $user->id]);
        Sanctum::actingAs($user);

        $this->postJson("/api/cameras/{$camera->id}/disconnect")
            ->assertStatus(200);

        $this->assertSame(Camera::STATUS_OFFLINE, $camera->fresh()->status);
    }

    public function test_unauthenticated_camera_list_is_rejected(): void
    {
        $this->getJson('/api/cameras')->assertStatus(401);
    }
}
