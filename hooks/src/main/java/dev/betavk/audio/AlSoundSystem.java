package dev.betavk.audio;

import net.minecraft.src.CodecMus;
import dev.betavk.hooks.Opts;
import dev.betavk.hooks.SoundCaptions;
import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALCCapabilities;
import paulscode.sound.ICodec;
import paulscode.sound.SoundBuffer;
import paulscode.sound.SoundSystemConfig;
import paulscode.sound.codecs.CodecJOrbis;
import paulscode.sound.codecs.CodecWav;

import javax.sound.sampled.AudioFormat;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Minimal OpenAL (LWJGL 3) replacement for the paulscode SoundSystem calls b1.7.3's SoundManager makes
 * (paulscode's LWJGL 2 OpenAL library cannot run on LWJGL 3). Uses paulscode's pure-Java Ogg/WAV codecs and the
 * game's .mus decoder. Every AL call happens on the game thread; only decoding runs on a worker thread.
 * Simplification: music is decoded whole (asynchronously) rather than streamed in chunks.
 */
public final class AlSoundSystem {
    private static final class Src {
        int al;                       // OpenAL source id, 0 until the buffer is ready
        boolean wantPlay;
        boolean loop;
        boolean relative;
        float vol = 1f, pitch = 1f, x, y, z, roll;
        float fadeDistance;
        Future<SoundBuffer> pending;  // async decode in flight
        boolean ownsBuffer;           // buffer not shared via cache: delete with the source
        boolean ambient;              // weather and cave sounds: they have their own volume
        String soundId;
        int buffer;
    }

    private long device, context;
    private boolean ok;
    private float listenerX, listenerY, listenerZ;
    private final Map<String, Src> sources = new HashMap<>();
    private final Map<String, Integer> bufferCache = new HashMap<>();
    private final ExecutorService decoder = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Sound decoder");
        t.setDaemon(true);
        return t;
    });

    public AlSoundSystem() {
        try {
            device = ALC10.alcOpenDevice((ByteBuffer) null);
            if (device == 0) throw new IllegalStateException("alcOpenDevice returned NULL (no audio device)");
            ALCCapabilities caps = ALC.createCapabilities(device);
            context = ALC10.alcCreateContext(device, (IntBuffer) null);
            ALC10.alcMakeContextCurrent(context);
            AL.createCapabilities(caps);
            ok = true;
            System.out.println("[betavk] OpenAL: " + AL10.alGetString(AL10.AL_RENDERER) + " / " + AL10.alGetString(AL10.AL_VERSION));
        } catch (Throwable t) {
            System.err.println("[betavk] OpenAL init failed, sound disabled: " + t);
        }
    }

    // ---- API used by SoundManager (same names as paulscode's SoundSystem) -----------------

    public void backgroundMusic(String name, URL url, String id, boolean loop) {
        create(name, url, loop, 0f, 0f, 0f, 0, 0f, true, false);
    }

    public void newSource(boolean priority, String name, URL url, String id, boolean loop,
                          float x, float y, float z, int model, float roll) {
        create(name, url, loop, x, y, z, model, roll, model == 0, true);
        markAmbient(name, id);
    }

    public void newStreamingSource(boolean priority, String name, URL url, String id, boolean loop,
                                   float x, float y, float z, int model, float roll) {
        create(name, url, loop, x, y, z, model, roll, model == 0, false);
        markAmbient(name, id);
    }

    private void markAmbient(String name, String id) {
        Src s = sources.get(name);
        if (s != null) {
            s.soundId = id;
            s.ambient = id != null && (id.startsWith("ambient.") || id.startsWith("ambient/"));
        }
    }

    private float gain(Src s) {
        float volume = s.vol * Opts.master * (s.ambient ? Opts.ambient : 1.0F);
        if (s.fadeDistance <= 0f) return volume;
        // Beta passes a fade distance for positional sounds. Paulscode applies this linear fade itself.
        float dx = s.x - listenerX, dy = s.y - listenerY, dz = s.z - listenerZ;
        float distance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        return volume * Math.max(0f, 1f - distance / s.fadeDistance);
    }

    /** The gain a source is played at (for tests). */
    public float gainOf(String name) {
        Src s = sources.get(name);
        return s == null ? -1f : gain(s);
    }

    /** Re-applies the volume sliders to every source that exists (a slider on the Music &amp; Sounds screen moved). */
    public void refreshVolumes() {
        for (Src s : sources.values()) if (s.al != 0) AL10.alSourcef(s.al, AL10.AL_GAIN, gain(s));
    }

    public void play(String name) {
        Src s = sources.get(name);
        if (s == null) return;
        s.wantPlay = true;
        if (s.al != 0) startPlayback(s);
    }

    public void stop(String name) {
        Src s = sources.get(name);
        if (s == null) return;
        s.wantPlay = false;
        if (s.al != 0) AL10.alSourceStop(s.al);
    }

    public boolean playing(String name) {
        pump();
        Src s = sources.get(name);
        if (s == null) return false;
        if (s.pending != null) return true;   // still decoding: counts as busy so music is not queued twice
        return s.al != 0 && AL10.alGetSourcei(s.al, AL10.AL_SOURCE_STATE) == AL10.AL_PLAYING;
    }

    public void setVolume(String name, float v) {
        Src s = sources.get(name);
        if (s == null) return;
        s.vol = v;
        if (s.al != 0) AL10.alSourcef(s.al, AL10.AL_GAIN, gain(s));
    }

    public void setPitch(String name, float p) {
        Src s = sources.get(name);
        if (s == null) return;
        s.pitch = p;
        if (s.al != 0) AL10.alSourcef(s.al, AL10.AL_PITCH, p);
    }

    public void setListenerPosition(float x, float y, float z) {
        if (!ok) return;
        if (x != listenerX || y != listenerY || z != listenerZ) {
            listenerX = x;
            listenerY = y;
            listenerZ = z;
            AL10.alListener3f(AL10.AL_POSITION, x, y, z);
            for (Src s : sources.values()) {
                if (s.al != 0 && s.fadeDistance > 0f) AL10.alSourcef(s.al, AL10.AL_GAIN, gain(s));
            }
        }
        pump();
    }

    public void setListenerOrientation(float lx, float ly, float lz, float ux, float uy, float uz) {
        if (!ok) return;
        AL10.alListenerfv(AL10.AL_ORIENTATION, new float[]{lx, ly, lz, ux, uy, uz});
    }

    public void cleanup() {
        decoder.shutdownNow();
        if (!ok) return;
        for (Src s : sources.values()) release(s);
        sources.clear();
        for (int b : bufferCache.values()) AL10.alDeleteBuffers(b);
        bufferCache.clear();
        ALC10.alcMakeContextCurrent(0);
        ALC10.alcDestroyContext(context);
        ALC10.alcCloseDevice(device);
        ok = false;
    }

    // ---- internals -----------------------------------------------------------------------

    private void create(String name, URL url, boolean loop, float x, float y, float z, int model, float roll,
                        boolean relative, boolean cacheable) {
        if (!ok) return;
        Src old = sources.remove(name);
        if (old != null) release(old);
        Src s = new Src();
        s.loop = loop;
        s.x = x;
        s.y = y;
        s.z = z;
        s.relative = relative;
        s.roll = model == SoundSystemConfig.ATTENUATION_ROLLOFF ? roll : 0f;
        s.fadeDistance = model == SoundSystemConfig.ATTENUATION_LINEAR ? roll : 0f;
        sources.put(name, s);
        if (cacheable) {
            Integer cached = bufferCache.get(url.toString());
            if (cached == null) {
                try {
                    cached = upload(decode(url));
                    bufferCache.put(url.toString(), cached);
                } catch (Exception e) {
                    System.err.println("[betavk] failed to decode " + url + ": " + e);
                    sources.remove(name);
                    return;
                }
            }
            attach(s, cached, false);
        } else {
            s.pending = decoder.submit(() -> decode(url));
        }
    }

    /** Finish async decodes and reap finished one-shot sources. Called each frame from the listener update. */
    private void pump() {
        if (!ok) return;
        for (Iterator<Map.Entry<String, Src>> it = sources.entrySet().iterator(); it.hasNext(); ) {
            Src s = it.next().getValue();
            if (s.pending != null && s.pending.isDone()) {
                Future<SoundBuffer> f = s.pending;
                s.pending = null;
                try {
                    attach(s, upload(f.get()), true);
                } catch (Exception e) {
                    System.err.println("[betavk] failed to decode music: " + e);
                    it.remove();
                    continue;
                }
            }
            if (s.al != 0 && !s.loop && !s.wantPlay
                    && AL10.alGetSourcei(s.al, AL10.AL_SOURCE_STATE) == AL10.AL_STOPPED) {
                release(s);
                it.remove();
            } else if (s.al != 0 && AL10.alGetSourcei(s.al, AL10.AL_SOURCE_STATE) == AL10.AL_STOPPED && s.wantPlay && !s.loop) {
                s.wantPlay = false;   // finished playing on its own; reaped next pump
            }
        }
    }

    private void attach(Src s, int buffer, boolean owns) {
        s.buffer = buffer;
        s.ownsBuffer = owns;
        s.al = AL10.alGenSources();
        AL10.alSourcei(s.al, AL10.AL_BUFFER, buffer);
        AL10.alSourcef(s.al, AL10.AL_GAIN, gain(s));
        AL10.alSourcef(s.al, AL10.AL_PITCH, s.pitch);
        AL10.alSource3f(s.al, AL10.AL_POSITION, s.x, s.y, s.z);
        AL10.alSourcef(s.al, AL10.AL_ROLLOFF_FACTOR, s.roll);
        AL10.alSourcei(s.al, AL10.AL_SOURCE_RELATIVE, s.relative ? AL10.AL_TRUE : AL10.AL_FALSE);
        AL10.alSourcei(s.al, AL10.AL_LOOPING, s.loop ? AL10.AL_TRUE : AL10.AL_FALSE);
        if (s.wantPlay) startPlayback(s);
    }

    private void startPlayback(Src s) {
        AL10.alSourcePlay(s.al);
        if (Opts.soundCaptions && !s.relative && s.soundId != null && gain(s) > 0f) {
            SoundCaptions.hear(s.soundId, s.x, s.z);
        }
    }

    private void release(Src s) {
        if (s.pending != null) s.pending.cancel(true);
        if (s.al != 0) {
            AL10.alSourceStop(s.al);
            AL10.alSourcei(s.al, AL10.AL_BUFFER, 0);
            AL10.alDeleteSources(s.al);
            s.al = 0;
        }
        if (s.ownsBuffer && s.buffer != 0) {
            AL10.alDeleteBuffers(s.buffer);
            s.buffer = 0;
        }
    }

    private static SoundBuffer decode(URL url) throws Exception {
        String p = url.getPath().toLowerCase();
        ICodec codec = p.endsWith(".mus") ? new CodecMus() : p.endsWith(".wav") ? new CodecWav() : new CodecJOrbis();
        if (!codec.initialize(url)) throw new IllegalStateException("codec could not open " + url);
        SoundBuffer sb = codec.readAll();
        codec.cleanup();
        if (sb == null || sb.audioData == null) throw new IllegalStateException("codec returned no data for " + url);
        return sb;
    }

    private static int upload(SoundBuffer sb) {
        AudioFormat f = sb.audioFormat;
        int channels = f.getChannels(), bits = f.getSampleSizeInBits();
        int fmt = channels == 1 ? (bits == 8 ? AL10.AL_FORMAT_MONO8 : AL10.AL_FORMAT_MONO16)
                : (bits == 8 ? AL10.AL_FORMAT_STEREO8 : AL10.AL_FORMAT_STEREO16);
        byte[] data = sb.audioData;
        ByteBuffer buf = BufferUtils.createByteBuffer(data.length);
        if (bits == 16 && f.isBigEndian()) {
            for (int i = 0; i + 1 < data.length; i += 2) {   // OpenAL wants little-endian
                buf.put(data[i + 1]).put(data[i]);
            }
        } else {
            buf.put(data);
        }
        buf.flip();
        int b = AL10.alGenBuffers();
        AL10.alBufferData(b, fmt, buf, (int) f.getSampleRate());
        return b;
    }
}
