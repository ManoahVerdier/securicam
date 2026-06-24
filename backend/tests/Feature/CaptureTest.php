<?php

namespace Tests\Feature;

use App\Models\Camera;
use App\Models\Capture;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Http\UploadedFile;
use Illuminate\Support\Facades\Storage;
use Laravel\Sanctum\Sanctum;
use Tests\TestCase;

class CaptureTest extends TestCase
{
    use RefreshDatabase;

    public function test_user_can_list_captures_for_own_camera(): void
    {
        $user = User::factory()->create();
        $camera = Camera::factory()->create(['user_id' => $user->id]);
        Capture::factory()->count(5)->create(['camera_id' => $camera->id]);
        Sanctum::actingAs($user);

        $this->getJson("/api/cameras/{$camera->id}/captures")
            ->assertStatus(200)
            ->assertJsonPath('total', 5);
    }

    public function test_user_cannot_list_captures_for_another_users_camera(): void
    {
        $owner = User::factory()->create();
        $camera = Camera::factory()->create(['user_id' => $owner->id]);

        $attacker = User::factory()->create();
        Sanctum::actingAs($attacker);

        $this->getJson("/api/cameras/{$camera->id}/captures")->assertStatus(403);
    }

    public function test_user_can_upload_a_photo_capture(): void
    {
        Storage::fake('public');

        $user = User::factory()->create();
        $camera = Camera::factory()->online()->create(['user_id' => $user->id]);
        Sanctum::actingAs($user);

        $file = UploadedFile::fake()->image('photo.jpg');

        $this->postJson('/api/captures/upload', [
            'camera_id' => $camera->id,
            'type' => 'photo',
            'file' => $file,
        ])->assertStatus(201)
            ->assertJsonPath('capture.type', 'photo');

        $this->assertDatabaseHas('captures', ['camera_id' => $camera->id, 'type' => 'photo']);
    }

    public function test_user_cannot_upload_capture_to_another_users_camera(): void
    {
        Storage::fake('public');

        $owner = User::factory()->create();
        $camera = Camera::factory()->online()->create(['user_id' => $owner->id]);

        $attacker = User::factory()->create();
        Sanctum::actingAs($attacker);

        $file = UploadedFile::fake()->image('photo.jpg');

        $this->postJson('/api/captures/upload', [
            'camera_id' => $camera->id,
            'type' => 'photo',
            'file' => $file,
        ])->assertStatus(403);
    }

    public function test_user_can_delete_own_capture(): void
    {
        Storage::fake('public');

        $user = User::factory()->create();
        $camera = Camera::factory()->create(['user_id' => $user->id]);
        $capture = Capture::factory()->photo()->create(['camera_id' => $camera->id]);
        Sanctum::actingAs($user);

        $this->deleteJson("/api/cameras/{$camera->id}/captures/{$capture->id}")
            ->assertStatus(200);

        $this->assertDatabaseMissing('captures', ['id' => $capture->id]);
    }

    public function test_user_cannot_delete_capture_from_another_users_camera(): void
    {
        $owner = User::factory()->create();
        $camera = Camera::factory()->create(['user_id' => $owner->id]);
        $capture = Capture::factory()->photo()->create(['camera_id' => $camera->id]);

        $attacker = User::factory()->create();
        Sanctum::actingAs($attacker);

        $this->deleteJson("/api/cameras/{$camera->id}/captures/{$capture->id}")
            ->assertStatus(403);

        $this->assertDatabaseHas('captures', ['id' => $capture->id]);
    }

    public function test_capture_cannot_be_accessed_across_cameras(): void
    {
        $user = User::factory()->create();
        $camera1 = Camera::factory()->create(['user_id' => $user->id]);
        $camera2 = Camera::factory()->create(['user_id' => $user->id]);
        $captureOnCamera1 = Capture::factory()->photo()->create(['camera_id' => $camera1->id]);
        Sanctum::actingAs($user);

        // Requesting camera2's captures/{id} where id belongs to camera1 should 404
        $this->getJson("/api/cameras/{$camera2->id}/captures/{$captureOnCamera1->id}")
            ->assertStatus(404);
    }
}
