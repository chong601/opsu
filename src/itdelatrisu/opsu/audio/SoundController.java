/*
 * opsu! - an open-source osu! client
 * Copyright (C) 2014, 2015 Jeffrey Han
 *
 * opsu! is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * opsu! is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with opsu!.  If not, see <http://www.gnu.org/licenses/>.
 */

package itdelatrisu.opsu.audio;

import itdelatrisu.opsu.ErrorHandler;
import itdelatrisu.opsu.Options;
import itdelatrisu.opsu.OsuHitObject;
import itdelatrisu.opsu.audio.HitSound.SampleSet;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.UnsupportedAudioFileException;

import org.newdawn.slick.util.ResourceLoader;

/**
 * Controller for all (non-music) sound components.
 * Note: Uses Java Sound because OpenAL lags too much for accurate hit sounds.
 */
public class SoundController {
	/** Interface for all (non-music) sound components. */
	public interface SoundComponent {
		/**
		 * Returns the Clip associated with the sound component.
		 * @return the Clip
		 */
		public MultiClip getClip();
	}

	/** Sample volume multiplier, from timing points [0, 1]. */
	private static float sampleVolumeMultiplier = 1f;

	/** The name of the current sound file being loaded. */
	private static String currentFileName;

	/** The number of the current sound file being loaded. */
	private static int currentFileIndex = -1;

	// This class should not be instantiated.
	private SoundController() {}

	/**
	 * Loads and returns a Clip from a resource.
	 * @param ref the resource name
	 * @param isMP3 true if MP3, false if WAV
	 * @return the loaded and opened clip
	 */
	private static MultiClip loadClip(String ref, boolean isMP3) {
		try {
			URL url = ResourceLoader.getResource(ref);

			// check for 0 length files
			InputStream in = url.openStream();
			if (in.available() == 0) {
				in.close();
				return new MultiClip(ref, null);
			}
			in.close();

			AudioInputStream audioIn = AudioSystem.getAudioInputStream(url);

			// GNU/Linux workaround
//			Clip clip = AudioSystem.getClip();
			AudioFormat format = audioIn.getFormat();
			if (isMP3) {
				AudioFormat decodedFormat = new AudioFormat(
						AudioFormat.Encoding.PCM_SIGNED, format.getSampleRate(), 16,
						format.getChannels(), format.getChannels() * 2, format.getSampleRate(), false);
				AudioInputStream decodedAudioIn = AudioSystem.getAudioInputStream(decodedFormat, audioIn);
				format = decodedFormat;
				audioIn = decodedAudioIn;
			}
			DataLine.Info info = new DataLine.Info(Clip.class, format);
			if (AudioSystem.isLineSupported(info)) {
				return new MultiClip(ref, audioIn);
			} else {
				// try to find closest matching line
				Clip clip = AudioSystem.getClip();
				AudioFormat[] formats = ((DataLine.Info) clip.getLineInfo()).getFormats();
				int bestIndex = -1;
				float bestScore = 0;
				float sampleRate = format.getSampleRate();
				if (sampleRate < 0)
					sampleRate = clip.getFormat().getSampleRate();
				float oldSampleRate = sampleRate;
				while (true) {
					for (int i = 0; i < formats.length; i++) {
						AudioFormat curFormat = formats[i];
						AudioFormat newFormat = new AudioFormat(
								sampleRate, curFormat.getSampleSizeInBits(),
								curFormat.getChannels(), true, curFormat.isBigEndian());
						formats[i] = newFormat;
						DataLine.Info newLine = new DataLine.Info(Clip.class, newFormat);
						if (AudioSystem.isLineSupported(newLine) &&
						    AudioSystem.isConversionSupported(newFormat, format)) {
							float score = 1
									+ (newFormat.getSampleRate() == sampleRate ? 5 : 0)
									+ (newFormat.getSampleSizeInBits() == format.getSampleSizeInBits() ? 5 : 0)
									+ (newFormat.getChannels() == format.getChannels() ? 5 : 0)
									+ (newFormat.isBigEndian() == format.isBigEndian() ? 1 : 0)
									+ newFormat.getSampleRate() / 11025
									+ newFormat.getChannels()
									+ newFormat.getSampleSizeInBits() / 8;
							if (score > bestScore) {
								bestIndex = i;
								bestScore = score;
							}
						}
					}
					if (bestIndex < 0) {
						if (oldSampleRate < 44100) {
							if (sampleRate > 44100)
								break;
							sampleRate *= 2;
						} else {
							if (sampleRate < 44100)
								break;
							sampleRate /= 2;
						}
					} else
						break;
				}
				if (bestIndex >= 0) {
					return new MultiClip(ref, AudioSystem.getAudioInputStream(formats[bestIndex], audioIn));
				} else
					// still couldn't find anything, try the default clip format
					return new MultiClip(ref, AudioSystem.getAudioInputStream(clip.getFormat(), audioIn));
			}
		} catch (UnsupportedAudioFileException | IOException | LineUnavailableException | RuntimeException e) {
			ErrorHandler.error(String.format("Failed to load file '%s'.", ref), e, true);
		}
		return null;
	}

	/**
	 * Returns the sound file name, with extension, by first looking through
	 * the skins directory and then the default resource locations.
	 * @param filename the base file name
	 * @return the full file name, or null if no file found
	 */
	private static String getSoundFileName(String filename) {
		String wav = String.format("%s.wav", filename), mp3 = String.format("%s.mp3", filename);
		File skinWAV = new File(Options.getSkinDir(), wav), skinMP3 = new File(Options.getSkinDir(), mp3);
		if (skinWAV.isFile())
			return skinWAV.getAbsolutePath();
		if (skinMP3.isFile())
			return skinMP3.getAbsolutePath();
		if (ResourceLoader.resourceExists(wav))
			return wav;
		if (ResourceLoader.resourceExists(mp3))
			return mp3;
		return null;
	}

	/**
	 * Loads all sound files.
	 */
	public static void init() {
		if (Options.isSoundDisabled())
			return;

		currentFileIndex = 0;

		// menu and game sounds
		for (SoundEffect s : SoundEffect.values()) {
			if ((currentFileName = getSoundFileName(s.getFileName())) == null) {
				ErrorHandler.error(String.format("Could not find sound file '%s'.", s.getFileName()), null, false);
				continue;
			}
			s.setClip(loadClip(currentFileName, currentFileName.endsWith(".mp3")));
			currentFileIndex++;
		}

		// hit sounds
		for (SampleSet ss : SampleSet.values()) {
			for (HitSound s : HitSound.values()) {
				String filename = String.format("%s-%s", ss.getName(), s.getFileName());
				if ((currentFileName = getSoundFileName(filename)) == null) {
					ErrorHandler.error(String.format("Could not find hit sound file '%s'.", filename), null, false);
					continue;
				}
				s.setClip(ss, loadClip(currentFileName, false));
				currentFileIndex++;
			}
		}

		currentFileName = null;
		currentFileIndex = -1;
	}

	/**
	 * Sets the sample volume (modifies the global sample volume).
	 * @param volume the sample volume [0, 1]
	 */
	public static void setSampleVolume(float volume) {
		if (volume >= 0f && volume <= 1f)
			sampleVolumeMultiplier = volume;
	}

	/**
	 * Plays a sound clip.
	 * @param clip the Clip to play
	 * @param volume the volume [0, 1]
	 */
	private static void playClip(MultiClip clip, float volume) {
		if (clip == null)  // clip failed to load properly
			return;

		if (volume > 0f) {
			try {
				clip.start(volume);
			} catch (LineUnavailableException e) {
				ErrorHandler.error(String.format("Could not start a clip '%s'.", clip.getName()), e, true);
			}
		}
	}

	/**
	 * Plays a sound.
	 * @param s the sound effect
	 */
	public static void playSound(SoundComponent s) {
		playClip(s.getClip(), Options.getEffectVolume() * Options.getMasterVolume());
	}

	/**
	 * Plays hit sound(s) using an OsuHitObject bitmask.
	 * @param hitSound the hit sound (bitmask)
	 * @param sampleSet the sample set
	 * @param additionSampleSet the 'addition' sample set
	 */
	public static void playHitSound(byte hitSound, byte sampleSet, byte additionSampleSet) {
		if (hitSound < 0)
			return;

		float volume = Options.getHitSoundVolume() * sampleVolumeMultiplier * Options.getMasterVolume();
		if (volume == 0f)
			return;

		// play all sounds
		HitSound.setSampleSet(sampleSet);
		playClip(HitSound.NORMAL.getClip(), volume);

		HitSound.setSampleSet(additionSampleSet);
		if ((hitSound & OsuHitObject.SOUND_WHISTLE) > 0)
			playClip(HitSound.WHISTLE.getClip(), volume);
		if ((hitSound & OsuHitObject.SOUND_FINISH) > 0)
			playClip(HitSound.FINISH.getClip(), volume);
		if ((hitSound & OsuHitObject.SOUND_CLAP) > 0)
			playClip(HitSound.CLAP.getClip(), volume);
	}

	/**
	 * Plays a hit sound.
	 * @param s the hit sound
	 */
	public static void playHitSound(SoundComponent s) {
		playClip(s.getClip(), Options.getHitSoundVolume() * sampleVolumeMultiplier * Options.getMasterVolume());
	}

	/**
	 * Returns the name of the current file being loaded, or null if none.
	 */
	public static String getCurrentFileName() {
		return (currentFileName != null) ? currentFileName : null;
	}

	/**
	 * Returns the progress of sound loading, or -1 if not loading.
	 * @return the completion percent [0, 100] or -1
	 */
	public static int getLoadingProgress() {
		if (currentFileIndex == -1)
			return -1;

		return currentFileIndex * 100 / (SoundEffect.SIZE + (HitSound.SIZE * SampleSet.SIZE));
	}
}
