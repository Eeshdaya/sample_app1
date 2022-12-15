/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.common.midi.synth;

import android.content.Context;
import android.media.midi.MidiReceiver;
import android.util.Log;

import com.example.android.common.midi.MidiConstants;
import com.example.android.common.midi.MidiEventScheduler;
import com.example.android.common.midi.MidiEventScheduler.MidiEvent;
import com.example.android.common.midi.MidiFramer;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;

/**
 * Very simple polyphonic, single channel synthesizer. It runs a background
 * thread that processes MIDI events and synthesizes audio.
 */
public class SynthEngine extends MidiReceiver {

    private static final String TAG = "SynthEngine";

    public static final int FRAME_RATE = 48000;
    private static final int FRAMES_PER_BUFFER = 240;
    private static final int SAMPLES_PER_FRAME = 2;

    private boolean go;
    private Thread mThread;
    private float[] mBuffer = new float[FRAMES_PER_BUFFER * SAMPLES_PER_FRAME];
    private float mFrequencyScaler = 1.0f;
    private float mBendRange = 2.0f; // semitones
    private int mProgram;

    private ArrayList<SynthVoice> mFreeVoices = new ArrayList<SynthVoice>();
    private Hashtable<Integer, SynthVoice>
            mVoices = new Hashtable<Integer, SynthVoice>();
    private MidiEventScheduler mEventScheduler;
    private MidiFramer mFramer;
    private MidiReceiver mReceiver = new MyReceiver();
    private SimpleAudioOutput mAudioOutput;

    public SynthEngine() {
        this(new SimpleAudioOutput());
    }

    public SynthEngine(SimpleAudioOutput audioOutput) {
        mReceiver = new MyReceiver();
        mFramer = new MidiFramer(mReceiver);
        mAudioOutput = audioOutput;
    }

    @Override
    public void onSend(byte[] data, int offset, int count, long timestamp)
            throws IOException {
        if (mEventScheduler != null) {
            if (!MidiConstants.isAllActiveSensing(data, offset, count)) {
                mEventScheduler.getReceiver().send(data, offset, count,
                        timestamp);
            }
        }
    }

    /**
     * MyReceiver receives midicommand and implements it, like turns sound on off, blends pitch or by default generates log
     * */

    /*
    * Date:15/12/2022 :
    * url: https://developer.android.com/reference/android/media/midi/package-summary#classes
    * this says -
    * MidiReceiver is Interface for sending and receiving data to and from a MIDI device.
    * So we need to replace this one as we don't have a midi device sending anything to us. we instead, will be
    * creating byte msgs i.e. data using timestamp, midicode and lookup table
    * and trigger some method like this onsend method.
    *
    * so synthEngine will have Receiver but another receiver that doesnot listen to a device,
    * instead, takes input as file pointed by user,
    * creates array of data and triggers them by differences in the timestamp of those individual msgs.
    *
     * */

    private class MyReceiver extends MidiReceiver {
        @Override
        public void onSend(byte[] data, int offset, int count, long timestamp)
                throws IOException {
            byte command = (byte) (data[0] & MidiConstants.STATUS_COMMAND_MASK);
            int channel = (byte) (data[0] & MidiConstants.STATUS_CHANNEL_MASK);
            switch (command) {
            case MidiConstants.STATUS_NOTE_OFF:
                noteOff(channel, data[1], data[2]);
                break;
            case MidiConstants.STATUS_NOTE_ON:
                noteOn(channel, data[1], data[2]);
                break;
            case MidiConstants.STATUS_PITCH_BEND:
                int bend = (data[2] << 7) + data[1];
                pitchBend(channel, bend);
                break;
            case MidiConstants.STATUS_PROGRAM_CHANGE:
                mProgram = data[1];
                mFreeVoices.clear();
                break;
            default:

                logMidiMessage(data, offset, count);
                break;
            }
        }
    }

    private class AlankarAppMidiFileParser {


        private List<String> getMidiCodesFromFile(Context context, String filename){
            ArrayList<String> midiCodesInFile = new ArrayList<>();

            BufferedReader bufferedReader = null;
            try {
                bufferedReader = new BufferedReader(new InputStreamReader
                        (context.getAssets().open(filename), StandardCharsets.UTF_8));
                String line;
                while ((line = bufferedReader.readLine()) != null) {
                    //add the lines in some arraylist if you want to set them.
                    midiCodesInFile.add(line);
                }
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                if (bufferedReader != null) {
                    try {
                        bufferedReader.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
            return midiCodesInFile;
        }

        private void triggerSynth(List<String> midiCodesListFromFile){
            try{
                if(midiCodesListFromFile==null||midiCodesListFromFile.isEmpty()){
                    return;
                }
                for(String midiCode: midiCodesListFromFile){
                    //parse code, create byte data, call the aboce switch case
                    byte[] buffer = new byte[32];
                    int numBytes = 0;
                    int channel = 3; // MIDI channels 1-16 are encoded as 0-15.
                    int group = 0;
                    buffer[numBytes++] = (byte)(0x90 + (channel - 1)); // note on
                    buffer[numBytes++] = (byte)60; // pitch is middle C
                    buffer[numBytes++] = (byte)127; // max velocity
                    int offset = 0;
                    final long NANOS_PER_SECOND = 1000000000L;
                    long now = System.nanoTime();
                    long future = now + (2 * NANOS_PER_SECOND);
                    onSend(buffer, offset, numBytes, future);
                }
            }catch (Exception e)
            {e.printStackTrace();}
        }

        public void onSend(byte[] data, int offset, int count, long timestamp)
                throws IOException {
            byte command = (byte) (data[0] & MidiConstants.STATUS_COMMAND_MASK);
            int channel = (byte) (data[0] & MidiConstants.STATUS_CHANNEL_MASK);
            switch (command) {
                case MidiConstants.STATUS_NOTE_OFF:
                    noteOff(channel, data[1], data[2]);
                    break;
                case MidiConstants.STATUS_NOTE_ON:
                    noteOn(channel, data[1], data[2]);
                    break;
                case MidiConstants.STATUS_PITCH_BEND:
                    int bend = (data[2] << 7) + data[1];
                    pitchBend(channel, bend);
                    break;
                case MidiConstants.STATUS_PROGRAM_CHANGE:
                    mProgram = data[1];
                    mFreeVoices.clear();
                    break;
                default:

                    logMidiMessage(data, offset, count);
                    break;
            }
        }
    }

    class MyRunnable implements Runnable {
        @Override
        public void run() {
            try {
                mAudioOutput.start(FRAME_RATE);
                onLoopStarted();
                while (go) {
                    processMidiEvents();
                    generateBuffer();
                    mAudioOutput.write(mBuffer, 0, mBuffer.length);
                    onBufferCompleted(FRAMES_PER_BUFFER);
                }
            } catch (Exception e) {
                Log.e(TAG, "SynthEngine background thread exception.", e);
            } finally {
                onLoopEnded();
                mAudioOutput.stop();
            }
        }
    }

    /**
     * This is called form the synthesis thread before it starts looping.
     */
    public void onLoopStarted() {
    }

    /**
     * This is called once at the end of each synthesis loop.
     *
     * @param framesPerBuffer
     */
    public void onBufferCompleted(int framesPerBuffer) {
    }

    /**
     * This is called form the synthesis thread when it stop looping.
     */
    public void onLoopEnded() {
    }

    /**
     * Assume message has been aligned to the start of a MIDI message.
     *
     * @param data
     * @param offset
     * @param count
     */
    public void logMidiMessage(byte[] data, int offset, int count) {
        String text = "Received: ";
        for (int i = 0; i < count; i++) {
            text += String.format("0x%02X, ", data[offset + i]);
        }
        Log.i(TAG, text);
    }

    /**
     * @throws IOException
     *
     */
    private void processMidiEvents() throws IOException {
        long now = System.nanoTime(); // TODO use audio presentation time
        MidiEvent event = (MidiEvent) mEventScheduler.getNextEvent(now);
        while (event != null) {
            mFramer.send(event.data, 0, event.count, event.getTimestamp());
            mEventScheduler.addEventToPool(event);
            event = (MidiEvent) mEventScheduler.getNextEvent(now);
        }
    }

    /**
     *
     */
    private void generateBuffer() {
        for (int i = 0; i < mBuffer.length; i++) {
            mBuffer[i] = 0.0f;
        }
        Iterator<SynthVoice> iterator = mVoices.values().iterator();
        while (iterator.hasNext()) {
            SynthVoice voice = iterator.next();
            if (voice.isDone()) {
                iterator.remove();
                // mFreeVoices.add(voice);
            } else {
                voice.mix(mBuffer, SAMPLES_PER_FRAME, 0.25f);
            }
        }
    }

    public void noteOff(int channel, int noteIndex, int velocity) {
        SynthVoice voice = mVoices.get(noteIndex);
        if (voice != null) {
            voice.noteOff();
        }
    }

    public void allNotesOff() {
        Iterator<SynthVoice> iterator = mVoices.values().iterator();
        while (iterator.hasNext()) {
            SynthVoice voice = iterator.next();
            voice.noteOff();
        }
    }

    /**
     * Create a SynthVoice.
     */
    public SynthVoice createVoice(int program) {
        // For every odd program number use a sine wave.
        if ((program & 1) == 1) {
            return new SineVoice();
        } else {
            return new SawVoice();
        }
    }

    /**
     *
     * @param channel
     * @param noteIndex
     * @param velocity
     */
    public void noteOn(int channel, int noteIndex, int velocity) {
        if (velocity == 0) {
            noteOff(channel, noteIndex, velocity);
        } else {
            mVoices.remove(noteIndex);
            SynthVoice voice;
            if (mFreeVoices.size() > 0) {
                voice = mFreeVoices.remove(mFreeVoices.size() - 1);
            } else {
                voice = createVoice(mProgram);
            }
            voice.setFrequencyScaler(mFrequencyScaler);
            voice.noteOn(noteIndex, velocity);
            mVoices.put(noteIndex, voice);
        }
    }

    public void pitchBend(int channel, int bend) {
        double semitones = (mBendRange * (bend - 0x2000)) / 0x2000;
        mFrequencyScaler = (float) Math.pow(2.0, semitones / 12.0);
        Iterator<SynthVoice> iterator = mVoices.values().iterator();
        while (iterator.hasNext()) {
            SynthVoice voice = iterator.next();
            voice.setFrequencyScaler(mFrequencyScaler);
        }
    }

    /**
     * Start the synthesizer.
     */
    public void start() {
        stop();
        go = true;
        mThread = new Thread(new MyRunnable());
        mEventScheduler = new MidiEventScheduler();
        mThread.start();
    }

    /**
     * Stop the synthesizer.
     */
    public void stop() {
        go = false;
        if (mThread != null) {
            try {
                mThread.interrupt();
                mThread.join(500);
            } catch (InterruptedException e) {
                // OK, just stopping safely.
            }
            mThread = null;
            mEventScheduler = null;
        }
    }
}
