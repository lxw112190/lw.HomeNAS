# Synthetic playback fixture

player-tone.mp3 is a generated two-second 440 Hz sine wave, not a user recording.

Generated with FFmpeg: `ffmpeg -f lavfi -i sine=frequency=440:sample_rate=48000 -t 2 -c:a libmp3lame -b:a 64k -write_xing 1 player-tone.mp3`.

Used only by Android instrumentation tests; androidTest assets are not part of the installed production APK.
