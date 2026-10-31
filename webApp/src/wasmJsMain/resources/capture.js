// Screen and audio capture for the browser version of NeonSync, used from WebCapture.kt.
globalThis.NeonSyncCapture = {
    /**
     * Asks which screen, window or tab to share, with its sound where the browser supports that
     * (Chrome/Edge). Resolves to the MediaStream, or to an error message for the user.
     */
    async open(onEnded) {
        let stream;
        try {
            stream = await navigator.mediaDevices.getDisplayMedia({ video: { frameRate: 30 }, audio: true });
        } catch (e) {
            if (e.name === "InvalidStateError") return "The browser allows screen sharing only right after a click. Turn the switch on.";
            if (e.name === "NotAllowedError") return "Screen sharing was not allowed";
            return "Screen sharing failed: " + e.message;
        }
        // Fires when sharing is stopped in the browser UI, not on track.stop()
        for (const track of stream.getTracks()) track.addEventListener("ended", () => onEnded(), { once: true });
        return stream;
    },

    /**
     * Opens an audio input. Firefox lets the user pick the device in its prompt; on Linux the
     * "Monitor of …" devices carry what the computer plays. Resolves to the MediaStream, or to an
     * error message for the user.
     */
    async openAudioInput(onEnded) {
        let stream;
        try {
            // The processing meant for calls would flatten music
            stream = await navigator.mediaDevices.getUserMedia({
                audio: { echoCancellation: false, noiseSuppression: false, autoGainControl: false, channelCount: 2 },
            });
        } catch (e) {
            if (e.name === "NotAllowedError") return "Audio input was not allowed";
            if (e.name === "NotFoundError") return "No audio input found";
            return "Audio input failed: " + e.message;
        }
        for (const track of stream.getTracks()) track.addEventListener("ended", () => onEnded(), { once: true });
        return stream;
    },

    close(stream) {
        for (const track of stream.getTracks()) track.stop();
    },

    hasAudio(stream) {
        return stream.getAudioTracks().length > 0;
    },

    /** Plays the video into a hidden element; grab() returns a downscaled RGBA frame, or null before the first one. */
    frameGrabber(stream, width, height) {
        const video = document.createElement("video");
        video.muted = true;
        video.playsInline = true;
        video.srcObject = new MediaStream(stream.getVideoTracks());
        // Rejects with an AbortError when stop() runs before playback started, e.g. when another
        // screen is selected; grab() simply returns no frames then
        video.play().catch((e) => {
            if (e.name !== "AbortError") console.warn("NeonSync: could not play the shared screen:", e);
        });
        const canvas = new OffscreenCanvas(width, height);
        const ctx = canvas.getContext("2d", { willReadFrequently: true });
        // Averages more source pixels than the default when shrinking a whole screen to a few pixels
        ctx.imageSmoothingQuality = "high";
        return {
            grab() {
                if (video.readyState < HTMLMediaElement.HAVE_CURRENT_DATA) return null;
                ctx.drawImage(video, 0, 0, width, height);
                return ctx.getImageData(0, 0, width, height).data;
            },
            stop() {
                video.pause();
                video.srcObject = null;
            },
        };
    },

    /** Calls onSamples with interleaved stereo Float32Arrays at 48 kHz. */
    audioTap(stream, onSamples) {
        const ctx = new AudioContext({ sampleRate: 48000 });
        const source = ctx.createMediaStreamSource(new MediaStream(stream.getAudioTracks()));
        // ScriptProcessorNode is deprecated, but unlike an AudioWorklet it needs no separate module, and the work per buffer is small
        const processor = ctx.createScriptProcessor(1024, 2, 2);
        processor.onaudioprocess = (e) => {
            const left = e.inputBuffer.getChannelData(0);
            const right = e.inputBuffer.numberOfChannels > 1 ? e.inputBuffer.getChannelData(1) : left;
            const out = new Float32Array(left.length * 2);
            for (let i = 0; i < left.length; i++) {
                out[2 * i] = left[i];
                out[2 * i + 1] = right[i];
            }
            onSamples(out);
        };
        source.connect(processor);
        // The processor only runs while connected; its output stays silent
        processor.connect(ctx.destination);
        ctx.resume().catch((e) => console.warn("NeonSync: could not start audio processing:", e));
        return {
            stop() {
                processor.disconnect();
                source.disconnect();
                ctx.close().catch(() => {});
            },
        };
    },
};
