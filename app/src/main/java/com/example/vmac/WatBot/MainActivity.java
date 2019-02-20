package com.example.vmac.WatBot;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.DefaultItemAnimator;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Toast;

import com.ibm.watson.developer_cloud.android.library.audio.MicrophoneHelper;
import com.ibm.watson.developer_cloud.android.library.audio.MicrophoneInputStream;
import com.ibm.watson.developer_cloud.android.library.audio.StreamPlayer;
import com.ibm.watson.developer_cloud.android.library.audio.utils.ContentType;
import com.ibm.watson.developer_cloud.assistant.v1.Assistant;
import com.ibm.watson.developer_cloud.assistant.v1.model.InputData;
import com.ibm.watson.developer_cloud.assistant.v1.model.MessageOptions;
import com.ibm.watson.developer_cloud.assistant.v1.model.MessageResponse;
import com.ibm.watson.developer_cloud.speech_to_text.v1.SpeechToText;
import com.ibm.watson.developer_cloud.speech_to_text.v1.model.RecognizeOptions;
import com.ibm.watson.developer_cloud.speech_to_text.v1.model.SpeechRecognitionResults;
import com.ibm.watson.developer_cloud.speech_to_text.v1.websocket.BaseRecognizeCallback;
import com.ibm.watson.developer_cloud.text_to_speech.v1.TextToSpeech;
import com.ibm.watson.developer_cloud.text_to_speech.v1.model.SynthesizeOptions;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;


public class MainActivity extends AppCompatActivity {


    private RecyclerView recyclerView;
    private ChatAdapter mAdapter;
    private ArrayList messageArrayList;
    private EditText inputMessage;
    private ImageButton btnSend;
    private ImageButton btnRecord;
    //private Map<String,Object> context = new HashMap<>();
    com.ibm.watson.developer_cloud.assistant.v1.model.Context context = null;
    StreamPlayer streamPlayer;
    private boolean initialRequest;
    private boolean permissionToRecordAccepted = false;
    private static final int REQUEST_RECORD_AUDIO_PERMISSION = 200;
    private static String TAG = "MainActivity";
    private static final int RECORD_REQUEST_CODE = 101;
    private boolean listening = false;
    private SpeechToText speechService;
    private MicrophoneInputStream capture;
    private SpeakerLabelsDiarization.RecoTokens recoTokens;
    private MicrophoneHelper microphoneHelper;
    //
    android.speech.tts.TextToSpeech mTTS;
    SpeechRecognizer speechRecognizer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        inputMessage = findViewById(R.id.message);
        btnSend = findViewById(R.id.btn_send);
        btnRecord= findViewById(R.id.btn_record);
        String customFont = "Montserrat-Regular.ttf";
        Typeface typeface = Typeface.createFromAsset(getAssets(), customFont);
        inputMessage.setTypeface(typeface);
        recyclerView = findViewById(R.id.recycler_view);

        messageArrayList = new ArrayList<>();
        mAdapter = new ChatAdapter(messageArrayList);
        microphoneHelper = new MicrophoneHelper(this);


        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true);
        recyclerView.setLayoutManager(layoutManager);
        recyclerView.setItemAnimator(new DefaultItemAnimator());
        recyclerView.setAdapter(mAdapter);
        this.inputMessage.setText("");
        this.initialRequest = true;
        sendMessage();

        //Watson Text-to-Speech Service on IBM Cloud
        final TextToSpeech textService = new TextToSpeech();
        //Use "apikey" as username and apikey values as password
        textService.setUsernameAndPassword("apikey", "4JYsCtSv0zuJriTcx4bGShcBfBj4Zxx-qclsgJrTy0t");
        textService.setEndPoint("https://gateway-lon.watsonplatform.net/assistant/api");

        int permission = ContextCompat.checkSelfPermission(this,
                Manifest.permission.RECORD_AUDIO);

        if (permission != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "Permission to record denied");
            makeRequest();
        }


        recyclerView.addOnItemTouchListener(new RecyclerTouchListener(getApplicationContext(), recyclerView, new ClickListener() {
            @Override
            public void onClick(View view, final int position) {
                Thread thread = new Thread(new Runnable() {
                    public void run() {
                        Message audioMessage;
                        try {

                            ////
                            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,1);
                            speechRecognizer.startListening(intent);
                            ////

                            audioMessage =(Message) messageArrayList.get(position);
                            streamPlayer = new StreamPlayer();
                            if(audioMessage != null && !audioMessage.getMessage().isEmpty()) {
                                SynthesizeOptions synthesizeOptions = new SynthesizeOptions.Builder()
                                        .text(audioMessage.getMessage())
                                        .voice(SynthesizeOptions.Voice.EN_US_LISAVOICE)
                                        .accept(SynthesizeOptions.Accept.AUDIO_WAV)
                                        .build();
                                streamPlayer.playStream(textService.synthesize(synthesizeOptions).execute());
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                });
                ininializeTTS();
                initializeSpeechRecognizer();
                thread.start();
            }

            @Override
            public void onLongClick(View view, int position) {
                recordMessage();

            }
        }));


        btnSend.setOnClickListener(new View.OnClickListener(){
            @Override
            public void onClick(View v) {
                if(checkInternetConnection()) {
                    sendMessage();
                }
            }
        });

        btnRecord.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                recordMessage();
            }
        });

    }
    private void initializeSpeechRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(this)){
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override
                public void onReadyForSpeech(Bundle params) {

                }

                @Override
                public void onBeginningOfSpeech() {

                }

                @Override
                public void onRmsChanged(float rmsdB) {

                }

                @Override
                public void onBufferReceived(byte[] buffer) {

                }

                @Override
                public void onEndOfSpeech() {

                }

                @Override
                public void onError(int error) {

                }

                @Override
                public void onResults(Bundle results) {
                    List<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    processResults(list.get(0));
                }

                @Override
                public void onPartialResults(Bundle partialResults) {

                }

                @Override
                public void onEvent(int eventType, Bundle params) {

                }
            });
        }
    }
    private void processResults(String command) {
        command = command.toLowerCase();

        if(command.indexOf("what")==-1){
            if(command.indexOf("your name")==-1){
                speak("The name is Uchiha Madara!");
            }
        }else if (command.indexOf("powerful")==-1){
            if(command.indexOf("how")==-1){
                speak("I'll show you wait!");
            }else if(command.indexOf("are")==-1){
                speak("Dont you want me to show you?");
            }
        }else if(command.indexOf("open")==-1){
            if(command.indexOf("browser")==-1){
                speak("I Madara, is opening your browser!");
                Intent intent = new Intent(Intent.ACTION_VIEW,Uri.parse("https://www.google.com"));
                startActivity(intent);
            }
        }
    }

    private void ininializeTTS() {
        mTTS = new android.speech.tts.TextToSpeech(this, new android.speech.tts.TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if(mTTS.getEngines().size()==0){
                    Toast.makeText(MainActivity.this, "NO Text to speech on the device!", Toast.LENGTH_SHORT).show();
                }else {
                    mTTS.setLanguage(new Locale("en","IN"));
                    speak("Varna chatri kaha kholni hai pata hai!");
                }
            }
        });
    }

    private void speak(String msg) {
        if(Build.VERSION.SDK_INT >= 21){
            mTTS.speak(msg, android.speech.tts.TextToSpeech.QUEUE_FLUSH,null,null);
        }else{
            mTTS.speak(msg, android.speech.tts.TextToSpeech.QUEUE_FLUSH,null);
        }
    }


    // Speech to Text Record Audio permission
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        switch (requestCode){
            case REQUEST_RECORD_AUDIO_PERMISSION:
                permissionToRecordAccepted  = grantResults[0] == PackageManager.PERMISSION_GRANTED;
                break;
            case RECORD_REQUEST_CODE: {

                if (grantResults.length == 0
                        || grantResults[0] !=
                        PackageManager.PERMISSION_GRANTED) {

                    Log.i(TAG, "Permission has been denied by user");
                } else {
                    Log.i(TAG, "Permission has been granted by user");
                }
                return;
            }
            case MicrophoneHelper.REQUEST_PERMISSION: {
                if (grantResults.length > 0 && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Permission to record audio denied", Toast.LENGTH_SHORT).show();
                }
            }
        }
        // if (!permissionToRecordAccepted ) finish();

    }

    protected void makeRequest() {
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.RECORD_AUDIO},
                MicrophoneHelper.REQUEST_PERMISSION);
    }


    // Sending a message to Watson Conversation Service
    private void sendMessage() {

        final String inputmessage = this.inputMessage.getText().toString().trim();
        if(!this.initialRequest) {
            Message inputMessage = new Message();
            inputMessage.setMessage(inputmessage);
            inputMessage.setId("1");
            messageArrayList.add(inputMessage);
        }
        else
        {
            Message inputMessage = new Message();
            inputMessage.setMessage(inputmessage);
            inputMessage.setId("100");
            this.initialRequest = false;
            Toast.makeText(getApplicationContext(),"Tap on the message for Voice",Toast.LENGTH_LONG).show();

        }

        this.inputMessage.setText("");
        mAdapter.notifyDataSetChanged();

        Thread thread = new Thread(new Runnable(){
            public void run() {
                try {

        Assistant assistantservice = new Assistant("2018-02-16");
        //If you like to use USERNAME AND PASSWORD
        //Your Username: "apikey", password: "<APIKEY_VALUE>"
        assistantservice.setUsernameAndPassword("apikey", "4JYsCtSv0zuJriTcx4bGShcBfBj4Zxx-qclsgJrTy0tX");

        //TODO: Uncomment this line if you want to use API KEY
        //assistantservice.setApiKey("<API_KEY_VALUE>");

        //Set endpoint which is the URL. Default value: https://gateway.watsonplatform.net/assistant/api
        assistantservice.setEndPoint("https://gateway-lon.watsonplatform.net/assistant/api");
        InputData input = new InputData.Builder(inputmessage).build();
        //WORKSPACES are now SKILLS
        MessageOptions options = new MessageOptions.Builder().workspaceId("1a89af48-9456-494a-8abd-1ca529fa0601").input(input).context(context).build();
        MessageResponse response = assistantservice.message(options).execute();
                    Log.i(TAG, "run: "+response);

                    String outputText = "";
                    int length=response.getOutput().getText().size();
                    Log.i(TAG, "run: "+length);
                    if(length>1) {
                        for (int i = 0; i < length; i++) {
                            outputText += '\n' + response.getOutput().getText().get(i).trim();
                        }
                    }
                    else
                       outputText = response.getOutput().getText().get(0);

                    Log.i(TAG, "run: "+outputText);
               //Passing Context of last conversation
                if(response.getContext() !=null)
                    {
                        //context.clear();
                        context = response.getContext();

                    }
        Message outMessage=new Message();
          if(response!=null)
          {
              if(response.getOutput()!=null && response.getOutput().containsKey("text"))
              {
                  ArrayList responseList = (ArrayList) response.getOutput().get("text");
                  if(null !=responseList && responseList.size()>0){
                      outMessage.setMessage(outputText);
                      outMessage.setId("2");
                  }
                  messageArrayList.add(outMessage);
              }

              runOnUiThread(new Runnable() {
                  public void run() {
                      mAdapter.notifyDataSetChanged();
                     if (mAdapter.getItemCount() > 1) {
                          recyclerView.getLayoutManager().smoothScrollToPosition(recyclerView, null, mAdapter.getItemCount()-1);

                      }

                  }
              });


          }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });

        thread.start();

    }
    //Record a message via Watson Speech to Text
    private void recordMessage() {
        speechService = new SpeechToText();
        //Use "apikey" as username and apikey as your password
        speechService.setUsernameAndPassword("apikey", "4JYsCtSv0zuJriTcx4bGShcBfBj4Zxx-qclsgJrTy0tX");
        //Default: https://stream.watsonplatform.net/text-to-speech/api
        speechService.setEndPoint("https://gateway-lon.watsonplatform.net/assistant/api");

        if(!listening) {
            capture = microphoneHelper.getInputStream(true);
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        speechService.recognizeUsingWebSocket(getRecognizeOptions(capture), new MicrophoneRecognizeDelegate());
                    } catch (Exception e) {
                        showError(e);
                    }
                }
            }).start();
            listening = true;
            Toast.makeText(MainActivity.this,"Listening....Click to Stop", Toast.LENGTH_LONG).show();

        } else {
            try {
                microphoneHelper.closeInputStream();
                listening = false;
                Toast.makeText(MainActivity.this,"Stopped Listening....Click to Start", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                e.printStackTrace();
            }

        }
    }

    /**
     * Check Internet Connection
     * @return
     */
    private boolean checkInternetConnection() {
        // get Connectivity Manager object to check connection
        ConnectivityManager cm =
                (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);

        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        boolean isConnected = activeNetwork != null &&
                activeNetwork.isConnectedOrConnecting();

        // Check for network connections
        if (isConnected){
            return true;
        }
       else {
            Toast.makeText(this, " No Internet Connection available ", Toast.LENGTH_LONG).show();
            return false;
        }

    }

    //Private Methods - Speech to Text
    private RecognizeOptions getRecognizeOptions(InputStream audio) {
        return new RecognizeOptions.Builder()
                .audio(audio)
                .contentType(ContentType.OPUS.toString())
                .model("en-US_BroadbandModel")
                .interimResults(true)
                .inactivityTimeout(2000)
                //TODO: Uncomment this to enable Speaker Diarization
                //.speakerLabels(true)
                .build();
    }

    private class MicrophoneRecognizeDelegate extends BaseRecognizeCallback {

        @Override
        public void onTranscription(SpeechRecognitionResults speechResults) {
            System.out.println(speechResults);
            //TODO: Uncomment this to enable Speaker Diarization
            /*SpeakerLabelsDiarization.RecoTokens recoTokens = new SpeakerLabelsDiarization.RecoTokens();
            if(speechResults.getSpeakerLabels() !=null)
            {
                recoTokens.add(speechResults);
                Log.i("SPEECHRESULTS",speechResults.getSpeakerLabels().get(0).toString());


            }*/
            if(speechResults.getResults() != null && !speechResults.getResults().isEmpty()) {
                String text = speechResults.getResults().get(0).getAlternatives().get(0).getTranscript();
                showMicText(text);
            }
        }

        @Override public void onConnected() {

        }

        @Override public void onError(Exception e) {
            showError(e);
            enableMicButton();
        }

        @Override public void onDisconnected() {
            enableMicButton();
        }

        @Override
        public void onInactivityTimeout(RuntimeException runtimeException) {

        }

        @Override
        public void onListening() {

        }

        @Override
        public void onTranscriptionComplete() {

        }
    }

    private void showMicText(final String text) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                inputMessage.setText(text);
            }
        });
    }

    private void enableMicButton() {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                btnRecord.setEnabled(true);
            }
        });
    }

    private void showError(final Exception e) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                Toast.makeText(MainActivity.this, e.getMessage(), Toast.LENGTH_SHORT).show();
                e.printStackTrace();
            }
        });
    }

}

