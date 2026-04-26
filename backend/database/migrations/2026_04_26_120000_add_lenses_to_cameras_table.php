<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        Schema::table('cameras', function (Blueprint $table) {
            // List of physical lenses available on the phone, reported by the
            // Android app at connect/heartbeat time. Stored as JSON:
            //   [{"id":"0","facing":"back","label":"Caméra arrière (1)"}, ...]
            $table->json('available_lenses')->nullable()->after('connected_at');

            // Identifier of the lens currently used by the phone. Updated when
            // the user picks one from the web UI. Matches `id` in available_lenses.
            $table->string('active_lens', 64)->nullable()->after('available_lenses');
        });
    }

    public function down(): void
    {
        Schema::table('cameras', function (Blueprint $table) {
            $table->dropColumn(['available_lenses', 'active_lens']);
        });
    }
};
