/*****************************************************************************
 * PlaybackServiceClient.java
 *****************************************************************************
 * Copyright © 2011-2015 VLC authors and VideoLAN
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 *****************************************************************************/

package org.videolan.vlc;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.IBinder;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.util.Log;

import org.videolan.vlc.interfaces.IAudioPlayer;
import org.videolan.vlc.interfaces.IPlaybackService;
import org.videolan.vlc.interfaces.IPlaybackServiceCallback;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public class PlaybackServiceClient {
    public static final String TAG = "PlaybackServiceClient";

    private static PlaybackServiceClient mInstance;
    private static boolean mIsBound = false;
    private IPlaybackService mAudioServiceBinder;
    private ServiceConnection mAudioServiceConnection;
    private final ArrayList<IAudioPlayer> mAudioPlayer;
    private final ArrayList<MediaPlayedListener> mMediaPlayedListener;

    public interface MediaPlayedListener {
        public void onMediaPlayedAdded(MediaWrapper media, int index);
        public void onMediaPlayedRemoved(int index);
    }

    private final IPlaybackServiceCallback mCallback = new IPlaybackServiceCallback.Stub() {
        @Override
        public void update() throws RemoteException {
            updateAudioPlayer();
        }

        @Override
        public void updateProgress() throws RemoteException {
            updateProgressAudioPlayer();
        }

        @Override
        public void onMediaPlayedAdded(MediaWrapper media, int index) throws RemoteException {
            updateMediaPlayedAdded(media, index);
        }

        @Override
        public void onMediaPlayedRemoved(int index) throws RemoteException {
            updateMediaPlayedRemoved(index);
        }
    };

    private PlaybackServiceClient() {
        mAudioPlayer = new ArrayList<IAudioPlayer>();
        mMediaPlayedListener = new ArrayList<MediaPlayedListener>();
    }

    public static PlaybackServiceClient getInstance() {
        if (mInstance == null) {
            mInstance = new PlaybackServiceClient();
        }
        return mInstance;
    }

    /**
     * The connection listener interface for the audio service
     */
    public interface AudioServiceConnectionListener {
        public void onConnectionSuccess();
        public void onConnectionFailed();
    }

    /**
     * Bind to audio service if it is running
     */
    public void bindAudioService(Context context) {
        bindAudioService(context, null);
    }

    public void bindAudioService(Context context, final AudioServiceConnectionListener connectionListerner) {
        if (context == null) {
            Log.w(TAG, "bindAudioService() with null Context. Ooops" );
            return;
        }
        context = context.getApplicationContext();

        if (!mIsBound) {
            Intent service = new Intent(context, PlaybackService.class);

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            final boolean enableHS = prefs.getBoolean("enable_headset_detection", true);

            // Setup audio service connection
            mAudioServiceConnection = new ServiceConnection() {
                @Override
                public void onServiceDisconnected(ComponentName name) {
                    Log.d(TAG, "Service Disconnected");
                    mAudioServiceBinder = null;
                    mIsBound = false;
                }

                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    if (!mIsBound) // Can happen if unbind is called quickly before this callback
                        return;
                    Log.d(TAG, "Service Connected");
                    mAudioServiceBinder = IPlaybackService.Stub.asInterface(service);

                    // Register controller to the service
                    try {
                        mAudioServiceBinder.addAudioCallback(mCallback);
                        mAudioServiceBinder.detectHeadset(enableHS);
                        if (connectionListerner != null)
                            connectionListerner.onConnectionSuccess();
                    } catch (RemoteException e) {
                        Log.e(TAG, "remote procedure call failed: addAudioCallback()");
                        if (connectionListerner != null)
                            connectionListerner.onConnectionFailed();
                    }
                    updateAudioPlayer();
                }
            };

            mIsBound = context.bindService(service, mAudioServiceConnection, Context.BIND_AUTO_CREATE);
        } else {
            // Register controller to the service
            try {
                if (mAudioServiceBinder != null)
                    mAudioServiceBinder.addAudioCallback(mCallback);
                if (connectionListerner != null)
                    connectionListerner.onConnectionSuccess();
            } catch (RemoteException e) {
                Log.e(TAG, "remote procedure call failed: addAudioCallback()");
                if (connectionListerner != null)
                    connectionListerner.onConnectionFailed();
            }
        }
    }

    public void unbindAudioService(Context context) {
        if (context == null) {
            Log.w(TAG, "unbindAudioService() with null Context. Ooops" );
            return;
        }
        context = context.getApplicationContext();

        if (mIsBound) {
            mIsBound = false;
            try {
                if (mAudioServiceBinder != null)
                    mAudioServiceBinder.removeAudioCallback(mCallback);
            } catch (RemoteException e) {
                Log.e(TAG, "remote procedure call failed: removeAudioCallback()");
            }
            context.unbindService(mAudioServiceConnection);
            mAudioServiceBinder = null;
            mAudioServiceConnection = null;
        }
    }

    /**
     * Add a MediaPlayedListener
     * @param li
     */
    public void addMediaPlayedListener(MediaPlayedListener li) {
        if (!mMediaPlayedListener.contains(li))
            mMediaPlayedListener.add(li);
    }

    /**
     * Remove MediaPlayedListener from list
     * @param li
     */
    public void removeMediaPlayedListener(MediaPlayedListener li) {
        if (mMediaPlayedListener.contains(li))
            mMediaPlayedListener.remove(li);
    }

    /**
     * Add a AudioPlayer
     * @param ap
     */
    public void addAudioPlayer(IAudioPlayer ap) {
        if (!mAudioPlayer.contains(ap))
            mAudioPlayer.add(ap);
    }

    /**
     * Remove AudioPlayer from list
     * @param ap
     */
    public void removeAudioPlayer(IAudioPlayer ap) {
        if (mAudioPlayer.contains(ap))
            mAudioPlayer.remove(ap);
    }

    /**
     * Update all AudioPlayer
     */
    private void updateAudioPlayer() {
        for (IAudioPlayer player : mAudioPlayer)
            player.update();
    }

    /**
     * Update the progress of all AudioPlayers
     */
    private void updateProgressAudioPlayer() {
        for (IAudioPlayer player : mAudioPlayer)
            player.updateProgress();
    }

    private void updateMediaPlayedAdded(MediaWrapper media, int index) {
        for (MediaPlayedListener listener : mMediaPlayedListener) {
            listener.onMediaPlayedAdded(media, index);
        }
    }

    private void updateMediaPlayedRemoved(int index) {
        for (MediaPlayedListener listener : mMediaPlayedListener) {
            listener.onMediaPlayedRemoved(index);
        }
    }

    /**
     * This is a handy utility function to call remote procedure calls from mAudioServiceBinder
     * to reduce code duplication across methods of AudioServiceController.
     *
     * @param instance The instance of IPlaybackService to call, usually mAudioServiceBinder
     * @param returnType Return type of the method being called
     * @param defaultValue Default value to return in case of null or exception
     * @param functionName The function name to call, e.g. "stop"
     * @param parameterTypes List of parameter types. Pass null if none.
     * @param parameters List of parameters. Must be in same order as parameterTypes. Pass null if none.
     * @return The results of the RPC or defaultValue if error
     */
    private <T> T remoteProcedureCall(IPlaybackService instance, Class<T> returnType, T defaultValue, String functionName, Class<?> parameterTypes[], Object parameters[]) {
        if(instance == null) {
            return defaultValue;
        }

        try {
            Method m = IPlaybackService.class.getMethod(functionName, parameterTypes);
            @SuppressWarnings("unchecked")
            T returnVal = (T) m.invoke(instance, parameters);
            return returnVal;
        } catch(NoSuchMethodException e) {
            e.printStackTrace();
            return defaultValue;
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
            return defaultValue;
        } catch (IllegalAccessException e) {
            e.printStackTrace();
            return defaultValue;
        } catch (InvocationTargetException e) {
            if(e.getTargetException() instanceof RemoteException) {
                Log.e(TAG, "remote procedure call failed: " + functionName + "()");
            }
            return defaultValue;
        }
    }

    public void loadLocation(String mediaPath) {
        ArrayList < String > arrayList = new ArrayList<String>();
        arrayList.add(mediaPath);
        loadLocations(arrayList, 0);
    }


    public void load(MediaWrapper media, boolean forceAudio) {
        ArrayList<MediaWrapper> arrayList = new ArrayList<MediaWrapper>();
        arrayList.add(media);
        load(arrayList, 0, forceAudio);
    }

    public void load(MediaWrapper media) {
        load(media, false);
    }

    public void loadLocations(List<String> mediaPathList, int position) {
        remoteProcedureCall(mAudioServiceBinder, Void.class, (Void) null, "loadLocations",
                new Class<?>[]{List.class, int.class},
                new Object[]{mediaPathList, position});
    }

    public void load(List<MediaWrapper> mediaList, int position) {
        load(mediaList, position, false);
    }

    public void load(List<MediaWrapper> mediaList, int position, boolean forceAudio) {
        remoteProcedureCall(mAudioServiceBinder, Void.class, (Void) null, "load",
                new Class<?>[]{List.class, int.class, boolean.class},
                new Object[]{mediaList, position, forceAudio});
    }

    public void append(MediaWrapper media) {
        ArrayList<MediaWrapper> arrayList = new ArrayList<MediaWrapper>();
        arrayList.add(media);
        append(arrayList);
    }

    public void append(List<MediaWrapper> mediaList) {
        remoteProcedureCall(mAudioServiceBinder, Void.class, (Void) null, "append",
                new Class<?>[]{List.class},
                new Object[]{mediaList});
    }

    public void moveItem(int positionStart, int positionEnd) {
        remoteProcedureCall(mAudioServiceBinder, Void.class, (Void)null, "moveItem",
                new Class<?>[] { int.class, int.class },
                new Object[] { positionStart, positionEnd } );
    }

    public void remove(int position) {
        remoteProcedureCall(mAudioServiceBinder, Void.class, (Void)null, "remove",
                new Class<?>[] { int.class },
                new Object[] { position } );
    }

    public void removeLocation(String location) {
        remoteProcedureCall(mAudioServiceBinder, Void.class, (Void)null, "removeLocation",
                new Class<?>[] { String.class },
                new Object[] { location } );
    }

    @SuppressWarnings("unchecked")
    public List<MediaWrapper> getMedias() {
        return remoteProcedureCall(mAudioServiceBinder, List.class, null, "getMedias", null, null);
    }

    @SuppressWarnings("unchecked")
    public List<String> getMediaLocations() {
        List<String> def = new ArrayList<String>();
        return remoteProcedureCall(mAudioServiceBinder, List.class, def, "getMediaLocations", null, null);
    }

    public String getCurrentMediaLocation() {
        return remoteProcedureCall(mAudioServiceBinder, String.class, (String)null, "getCurrentMediaLocation", null, null);
    }

    public MediaWrapper getCurrentMediaWrapper() {
        return remoteProcedureCall(mAudioServiceBinder, MediaWrapper.class, (MediaWrapper)null, "getCurrentMediaWrapper", null, null);
    }

    public void stop() {
        remoteProcedureCall(mAudioServiceBinder, Void.class, (Void)null, "stop", null, null);
    }

    public void showWithoutParse(int u) {
        remoteProcedureCall(mAudioServiceBinder, Void.class, (Void)null, "showWithoutParse",
                new Class<?>[] { int.class },
                new Object[] { u } );
    }

    public void playIndex(int i) {
        remoteProcedureCall(mAudioServiceBinder, Void.class, (Void)null, "playIndex",
                new Class<?>[] { int.class },
                new Object[] { i } );
    }

    public String getAlbum() {
        return remoteProcedureCall(mAudioServiceBinder, String.class, (String)null, "getAlbum", null, null);
    }

    public String getArtist() {
        return remoteProcedureCall(mAudioServiceBinder, String.class, (String)null, "getArtist", null, null);
    }

    public String getArtistPrev() {
        return remoteProcedureCall(mAudioServiceBinder, String.class, (String)null, "getArtistPrev", null, null);
    }

    public String getArtistNext() {
        return remoteProcedureCall(mAudioServiceBinder, String.class, (String)null, "getArtistNext", null, null);
    }

    public String getTitle() {
        return remoteProcedureCall(mAudioServiceBinder, String.class, (String)null, "getTitle", null, null);
    }

    public String getTitlePrev() {
        return remoteProcedureCall(mAudioServiceBinder, String.class, (String)null, "getTitlePrev", null, null);
    }

    public String getTitleNext() {
        return remoteProcedureCall(mAudioServiceBinder, String.class, (String)null, "getTitleNext", null, null);
    }

    public boolean isPlaying() {
        return hasMedia() && remoteProcedureCall(mAudioServiceBinder, boolean.class, false, "isPlaying", null, null);
    }

    public void pause() {
        remoteProcedureCall(mAudioServiceBinder, Void.class, (Void)null, "pause", null, null);
    }

    public void play() {
        remoteProcedureCall(mAudioServiceBinder, Void.class, (Void)null, "play", null, null);
    }

    public boolean hasMedia() {
        return remoteProcedureCall(mAudioServiceBinder, boolean.class, false, "hasMedia", null, null);
    }

    public int getLength() {
        return remoteProcedureCall(mAudioServiceBinder, int.class, 0, "getLength", null, null);
    }

    public int getTime() {
        return remoteProcedureCall(mAudioServiceBinder, int.class, 0, "getTime", null, null);
    }

    public Bitmap getCover() {
        return remoteProcedureCall(mAudioServiceBinder, Bitmap.class, (Bitmap)null, "getCover", null, null);
    }

    public Bitmap getCoverPrev() {
        return remoteProcedureCall(mAudioServiceBinder, Bitmap.class, (Bitmap)null, "getCoverPrev", null, null);
    }

    public Bitmap getCoverNext() {
        return remoteProcedureCall(mAudioServiceBinder, Bitmap.class, (Bitmap)null, "getCoverNext", null, null);
    }

    public void next() {
        remoteProcedureCall(mAudioServiceBinder, Void.class, (Void)null, "next", null, null);
    }

    public void previous() {
        remoteProcedureCall(mAudioServiceBinder, Void.class, (Void)null, "previous", null, null);
    }

    public void setTime(long time) {
        remoteProcedureCall(mAudioServiceBinder, Void.class, (Void)null, "setTime",
                new Class<?>[] { long.class },
                new Object[] { time } );
    }

    public boolean hasNext() {
        return remoteProcedureCall(mAudioServiceBinder, boolean.class, false, "hasNext", null, null);
    }

    public boolean hasPrevious() {
        return remoteProcedureCall(mAudioServiceBinder, boolean.class, false, "hasPrevious", null, null);
    }

    public void shuffle() {
        remoteProcedureCall(mAudioServiceBinder, Void.class, (Void)null, "shuffle", null, null);
    }

    public void setRepeatType(PlaybackService.RepeatType t) {
        remoteProcedureCall(mAudioServiceBinder, Void.class, (Void)null, "setRepeatType",
                new Class<?>[] { int.class },
                new Object[] { t.ordinal() } );
    }

    public boolean isShuffling() {
        return remoteProcedureCall(mAudioServiceBinder, boolean.class, false, "isShuffling", null, null);
    }

    public PlaybackService.RepeatType getRepeatType() {
        return PlaybackService.RepeatType.values()[
            remoteProcedureCall(mAudioServiceBinder, int.class, PlaybackService.RepeatType.None.ordinal(), "getRepeatType", null, null)
        ];
    }

    public void detectHeadset(boolean enable) {
        remoteProcedureCall(mAudioServiceBinder, Void.class, null, "detectHeadset",
                new Class<?>[] { boolean.class },
                new Object[] { enable } );
    }

    public float getRate() {
        return remoteProcedureCall(mAudioServiceBinder, Float.class, (float) 1.0, "getRate", null, null);
    }

    public void handleVout() {
        remoteProcedureCall(mAudioServiceBinder, Void.class, (Void)null, "handleVout", null, null);
    }
}
