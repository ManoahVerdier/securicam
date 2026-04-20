<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    /**
     * Run the migrations.
     */
    public function up(): void
    {
        Schema::create('captures', function (Blueprint $table) {
            $table->id();
            $table->foreignId('camera_id')->constrained()->onDelete('cascade');
            $table->enum('type', ['photo', 'video']);
            $table->string('file_path');
            $table->string('thumbnail_path')->nullable();
            $table->unsignedInteger('duration')->nullable()->comment('Duration in seconds for videos');
            $table->unsignedBigInteger('file_size')->nullable()->comment('File size in bytes');
            $table->timestamp('captured_at');
            $table->timestamps();

            $table->index(['camera_id', 'type']);
            $table->index(['camera_id', 'captured_at']);
        });
    }

    /**
     * Reverse the migrations.
     */
    public function down(): void
    {
        Schema::dropIfExists('captures');
    }
};
