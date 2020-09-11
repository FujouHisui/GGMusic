package tech.hisui.ggmusic;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.provider.MediaStore;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.io.IOException;

public class MainActivity extends AppCompatActivity implements View.OnClickListener {
    public static final String DATA_URI = "tech.hisui.ggmusic.DATA_URI";
    public static final String TITLE = "tech.hisui.ggmusic.TITLE";
    public static final String ARTIST = "tech.hisui.ggmusic.ARTIST";
    public static final String ACTION_MUSIC_START =
            "tech.hisui.ggmusic.ACTION_MUSIC_START";
    public static final String ACTION_MUSIC_STOP =
            "tech.hisui.ggmusic.ACTION_MUSIC_STOP";
    public static final int UPDATE_PROGRESS = 1;
    public int albumArtIndex = 0;
    private static final String TAG = MainActivity.class.getSimpleName();
    private ContentResolver mContentResolver;
    private ListView mPlaylist;
    private MediaCursorAdapter mCursorAdapter;
    private final String SELECTION =
            MediaStore.Audio.Media.IS_MUSIC + " = ? " + " AND " +
                    MediaStore.Audio.Media.MIME_TYPE + " LIKE ? ";
    private final String[] SELECTION_ARGS = {
            Integer.toString(1),
            "audio/mpeg"
    };

    private final int REQUEST_EXTERNAL_STORAGE = 1;
    private static String[] PERMISSIONS_STORAGE = {
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
    };

    private BottomNavigationView navigation;
    private TextView tvBottomTitle;
    private TextView tvBottomArtist;
    private ImageView ivAlbumThumbnail;
    private ImageView ivPlay;

    private MediaPlayer mMediaPlayer = null;

    private MusicService mService;
    public boolean mBound = false;
    private boolean mPlayStatus = true;
    public ProgressBar pbProgress;
    private MusicReceiver musicReceiver;

    private Handler mHandler = new Handler(Looper.getMainLooper()){
        public void handleMessage(Message msg){
            switch (msg.what){
                case UPDATE_PROGRESS:
                    int position = msg.arg1;
                    pbProgress.setProgress(position);
                    break;
                default:
                    break;
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mPlaylist = findViewById(R.id.lv_playlist);
        mContentResolver = getContentResolver();
        mCursorAdapter = new MediaCursorAdapter(MainActivity.this);
        mPlaylist.setAdapter(mCursorAdapter);

        navigation = findViewById(R.id.navigation);
        LayoutInflater.from(MainActivity.this)
                .inflate(R.layout.bottom_media_toolbar,
                        navigation, true);
        ivPlay = navigation.findViewById(R.id.iv_play);
        tvBottomTitle = navigation.findViewById(R.id.tv_bottom_title);
        tvBottomArtist = navigation.findViewById(R.id.tv_bottom_artist);
        ivAlbumThumbnail = navigation.findViewById(R.id.iv_thumbnail);
        pbProgress = navigation.findViewById(R.id.progress);

        if (ivPlay != null){
            ivPlay.setOnClickListener(MainActivity.this);
        }
            navigation.setVisibility(View.GONE);
        if (mPlayStatus && mMediaPlayer != null)
            navigation.setVisibility(View.VISIBLE);

        mPlaylist.setOnItemClickListener(itemClickListener);

        if(mMediaPlayer == null){
            mMediaPlayer = new MediaPlayer();
            Log.d(TAG, "MediaPlayer instance created!");
        }

        if(ContextCompat.checkSelfPermission(this,
                Manifest.permission.READ_EXTERNAL_STORAGE)!=
                PackageManager.PERMISSION_GRANTED){
            if (ActivityCompat.shouldShowRequestPermissionRationale(
                    MainActivity.this,
                    Manifest.permission.READ_EXTERNAL_STORAGE)){
            }else {
                requestPermissions(PERMISSIONS_STORAGE,
                        REQUEST_EXTERNAL_STORAGE);
            }
        }else {
            initPlaylist();
        }
        musicReceiver = new MusicReceiver();
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ACTION_MUSIC_START);
        intentFilter.addAction(ACTION_MUSIC_STOP);
        registerReceiver(musicReceiver, intentFilter);
    }


    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        switch (requestCode){
            case REQUEST_EXTERNAL_STORAGE:
                if(grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED){
                    initPlaylist();
                }
                break;
            default:
                break;
        }
    }

    @Override
    public void onClick(View view) {
        switch (view.getId()){
            case R.id.iv_play:
                mPlayStatus = !mPlayStatus;
                if (mPlayStatus == true){
                    mService.play();
                    ivPlay.setImageResource(
                            R.drawable.ic_baseline_pause_circle_outline_24);
                }else {
                    mService.pause();
                    ivPlay.setImageResource(
                            R.drawable.ic_baseline_play_circle_outline_24);
                }
                break;
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        Intent intent = new Intent(
                MainActivity.this, MusicService.class);
        bindService(intent, mConn, Context.BIND_AUTO_CREATE);

    }

    @Override
    protected void onStop() {
        unbindService(mConn);
        mBound = false;
        super.onStop();
    }

    protected void onDestroy() {
        if (mMediaPlayer != null){
            mMediaPlayer.stop();
            mMediaPlayer.release();
            mMediaPlayer = null;
        }
        unregisterReceiver(musicReceiver);
        super.onDestroy();
    }

    private ListView.OnItemClickListener itemClickListener =
            new ListView.OnItemClickListener(){
        @Override
        public void onItemClick(AdapterView<?> adapterView,
                                View view, int i, long l) {
            Cursor cursor = mCursorAdapter.getCursor();
            if(cursor != null && cursor.moveToPosition(i)){
                int titleIndex = cursor.getColumnIndex(
                        MediaStore.Audio.Media.TITLE);
                int artistIndex = cursor.getColumnIndex(
                        MediaStore.Audio.Media.ARTIST);
                int albumIdIndex = cursor.getColumnIndex(
                        MediaStore.Audio.Media.ALBUM_ID);
                int dataIndex = cursor.getColumnIndex(
                        MediaStore.Audio.Media.DATA);

                String title = cursor.getString(titleIndex);
                String artist = cursor.getString(artistIndex);
                Long albumId = cursor.getLong(albumIdIndex);
                String data = cursor.getString(dataIndex);

                Uri dataUri = Uri.parse(data);

                Intent serviceIntent = new Intent(
                        MainActivity.this,
                        MusicService.class);
                serviceIntent.putExtra(MainActivity.DATA_URI, data);
                serviceIntent.putExtra(MainActivity.TITLE, title);
                serviceIntent.putExtra(MainActivity.ARTIST, artist);
                startService(serviceIntent);

                navigation.setVisibility(View.VISIBLE);

                if (tvBottomTitle != null)
                    tvBottomTitle.setText(title);
                if (tvBottomArtist != null)
                    tvBottomArtist.setText(artist);
                mPlayStatus = true;
                ivPlay.setImageResource(
                        R.drawable.ic_baseline_pause_circle_outline_24);
                Uri albumUri = ContentUris.withAppendedId(
                        MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
                        albumId);

                Cursor albumCursor = mContentResolver.query(
                        albumUri,
                        null,
                        null,
                        null,
                        null);
                if (albumCursor != null && albumCursor.getCount() > 0){
                    albumCursor.moveToFirst();
                    albumArtIndex = albumCursor.getColumnIndex(
                            MediaStore.Audio.Albums.ALBUM_ART);
                    String albumArt = albumCursor.getString(
                            albumArtIndex);
                    Glide.with(MainActivity.this).
                            load(albumArt).into(ivAlbumThumbnail);
                    albumCursor.close();
                }
            }

        }
    };

    private void initPlaylist(){
        Cursor cursor = mContentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                null,
                SELECTION,
                SELECTION_ARGS,
                MediaStore.Audio.Media.DEFAULT_SORT_ORDER
        );
        if (cursor != null) {
            mCursorAdapter.swapCursor(cursor);
            mCursorAdapter.notifyDataSetInvalidated();
        }
    }

    private ServiceConnection mConn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder iBinder) {
            MusicService.MusicServiceBinder binder =
                    (MusicService.MusicServiceBinder) iBinder;
            mService = binder.getService();
            mBound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mService = null;
            mBound = false;
        }
    };

    private class MusicProgressRunnable implements Runnable{
        public MusicProgressRunnable(){
        }
        @Override
        public void run() {
            boolean mThreadWorking = true;
            while (mThreadWorking){
                try {
                    if(mService != null){
                        int position =
                                mService.getCurrentPosition();
                        Message message = new Message();
                        message.what = UPDATE_PROGRESS;
                        message.arg1 = position;
                        mHandler.sendMessage(message);
                        Log.d(TAG, "CurrentPosition: " + position);
                    }
                    mThreadWorking = mService.isPlaying();
                    Thread.sleep(100);
                }catch (InterruptedException ie){
                    ie.printStackTrace();
                }
            }
        }
    }
    public class MusicReceiver extends BroadcastReceiver{

        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(ACTION_MUSIC_START)) {
                if (mService != null) {
                    pbProgress.setMax(mService.getDuration());
                    new Thread(new MusicProgressRunnable()).start();
                }
            }
        }
    }
}