package paul.cipherresfeber.doodleme.Fragments;

import android.annotation.SuppressLint;
import android.graphics.PointF;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.speech.tts.TextToSpeech;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Random;

import paul.cipherresfeber.doodleme.CNNModel.PredictionListener;
import paul.cipherresfeber.doodleme.CNNModel.Predictor;
import paul.cipherresfeber.doodleme.CustomData.LabelProbability;
import paul.cipherresfeber.doodleme.R;
import paul.cipherresfeber.doodleme.Utility.Constants;
import paul.cipherresfeber.doodleme.Utility.DoodleDrawingKeeper;
import paul.cipherresfeber.doodleme.Views.DrawModel;
import paul.cipherresfeber.doodleme.Views.DrawingCanvas;

public class DoodleDrawingFragment extends Fragment implements View.OnTouchListener, PredictionListener {

    // todo: use this interface to return results
    private DoodleDrawingKeeper doodleDrawingKeeper;

    private final float MIN_THRESHOLD_PROBABILITY = (float) 0.15;
    private final int NUMBER_OF_TOP_PREDICTIONS = 5;

    private TextToSpeech speechEngine;
    private String doodleName;
    private boolean stopPredicting;

    private DrawingCanvas drawingCanvas;
    private DrawModel drawModel;
    private PointF mTmpPoint = new PointF();
    private float mLastX;
    private float mLastY;

    private Predictor predictor;
    private Random random;

    private TextView textViewPredictions;
    private TextView textViewCountDownTimer;

    private CountDownTimer countDownTimer;

    public static DoodleDrawingFragment newInstance(String doodleName, DoodleDrawingKeeper doodleDrawingKeeper) {
        DoodleDrawingFragment fragment = new DoodleDrawingFragment();
        Bundle args = new Bundle();
        args.putString(Constants.DOODLE_NAME, doodleName);
        args.putSerializable(Constants.DOODLE_RESULT_KEEPER_INTERFACE, doodleDrawingKeeper);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            doodleName = getArguments().getString(Constants.DOODLE_NAME);
            doodleDrawingKeeper = (DoodleDrawingKeeper) getArguments()
                    .getSerializable(Constants.DOODLE_RESULT_KEEPER_INTERFACE);
        }

        // instantiate the predictor
        predictor = new Predictor(Constants.TFLITE_MODEL_NAME,
                Constants.MODEL_LABEL_FILE_NAME, getContext(), this);

        // initialize the speech engine
        speechEngine = new TextToSpeech(getContext(), new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if(status == TextToSpeech.SUCCESS){
                    int result = speechEngine.setLanguage(Locale.getDefault());
                    if(result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED){
                        Toast.makeText(getContext(),
                                "Speech not supported", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        });

        // speech engine settings
        speechEngine.setSpeechRate(0.9f);
        speechEngine.setPitch(1f);

        // instantiate for random number generation
        random = new Random();

    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_doodle_drawing, container, false);

        textViewPredictions = view.findViewById(R.id.txvPredictions);

        // method to initialize the canvas drawing and our cnn model
        initializeCanvas(view);

        // the button to clear out the canvas
        Button buttonClearDrawingCanvas = view.findViewById(R.id.btnClearDrawingCanvas);
        buttonClearDrawingCanvas.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                drawModel.clear();
                drawingCanvas.reset();
                drawingCanvas.invalidate();
                textViewPredictions.setText("");
            }
        });

        Button buttonCloseCanvas = view.findViewById(R.id.btnCloseCanvas);
        buttonCloseCanvas.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                // well if user is quitting -- he could not draw the doodle
                doodleDrawingKeeper.keepResult(doodleName, false, drawingCanvas.getBitmap());
                getActivity().getSupportFragmentManager().beginTransaction()
                        .setCustomAnimations(R.anim.enter_from_top, R.anim.exit_to_top)
                        .remove(DoodleDrawingFragment.this).commit();
                countDownTimer.cancel();

            }
        });

        textViewCountDownTimer = view.findViewById(R.id.txvCountDownTimer);

        // start a count down timer to maintain time during gameplay
        countDownTimer = new CountDownTimer(1000*10 /* 10s */ + 500 /* bonus time */,1000 /* 1s */){
            @Override
            public void onTick(long millisUntilFinished) {
                textViewCountDownTimer.setText(String.valueOf(millisUntilFinished/1000 /* seconds until finished */));
            }

            @Override
            public void onFinish() {

                // well if time has run up --- the user could not have drawn the correct doodle
                doodleDrawingKeeper.keepResult(doodleName, false, drawingCanvas.getBitmap());
                getActivity().getSupportFragmentManager().beginTransaction()
                        .setCustomAnimations(R.anim.enter_from_top, R.anim.exit_to_top)
                        .remove(DoodleDrawingFragment.this).commit();

            }
        }.start();

        return view;
    }

    // after a prediction is made this method is invoked
    @Override
    public void predictionCallback(ArrayList<LabelProbability> topPredictions) {

        boolean success = false;

        for(int i=0; i<topPredictions.size(); i++){
/*
            Toast.makeText(getContext(),
                    topPredictions.get(i).getLabelName() + " - " + doodleName, Toast.LENGTH_SHORT).show();
*/
            if(topPredictions.get(i).getLabelName().equals(doodleName)){
                // then the user has correctly drawn the doodle
                countDownTimer.cancel();
                success = true;
                handleSuccess(doodleName);
                break;
            }
        }

        if(!success && !stopPredicting)
            handleFailure(topPredictions);

    }

    // if the doodle is correctly drawn, this method is invoked
    private void handleSuccess(final String doodleName){
        stopPredicting = true; // the model won't predict anything now

        String[] precedingSpeech = {
                "Oh I know! It's ",
                "Oh I know! It's ",
                "Oh I know! It's ",
                "Gotcha, it's ",
                "I got it. It's a cute ",
                "I got it, it's "
        };

        String finalSpeech = precedingSpeech[random.nextInt(precedingSpeech.length)] +
                join(doodleName.split("_"), " ");

        speechEngine.speak(
                finalSpeech,
                TextToSpeech.QUEUE_FLUSH, null);

        textViewPredictions.setText(finalSpeech);

        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                // user successfully guessed the doodle
                doodleDrawingKeeper.keepResult(doodleName, true, drawingCanvas.getBitmap());
                getActivity().getSupportFragmentManager().beginTransaction()
                        .setCustomAnimations(R.anim.enter_from_top, R.anim.exit_to_top)
                        .remove(DoodleDrawingFragment.this).commit();
            }
        }, 2000); // wait for 2 second before quiting

    }

    // if the drawn doodle is not correct, this method is invoked
    private void handleFailure(ArrayList<LabelProbability> predictions){

        String[] precedingSpeech = {
                "Well, I see ",
                "I can see ",
                "I guess it's "
        };

        StringBuilder builder = new StringBuilder();
        for(int i=0; i<predictions.size(); i++){
            if(predictions.get(i).getProbability() > MIN_THRESHOLD_PROBABILITY){
                builder.append(join(predictions.get(i).getLabelName().split("_"), " "))
                        .append(", or ");
            }
        }

        if(!builder.toString().isEmpty()){
            String finalSpeech = precedingSpeech[random.nextInt(precedingSpeech.length)] +
                    builder.substring(0,builder.length()-5);

            textViewPredictions.setText(finalSpeech);
            speechEngine.speak(finalSpeech, TextToSpeech.QUEUE_FLUSH, null);

        } else{

            String[] couldNotGuessDrawingSpeech = {
                    "I have no idea what you are drawing!",
                    "What is this? No clue!",
                    "Well I am paranoid by your drawing!"
            };

            String finalSpeech = couldNotGuessDrawingSpeech[random.nextInt(couldNotGuessDrawingSpeech.length)];

            textViewPredictions.setText(finalSpeech);
            speechEngine.speak(finalSpeech,
                    TextToSpeech.QUEUE_FLUSH, null);
        }

    }

    public static String join(String[] arr, String separator) {
        StringBuilder sbStr = new StringBuilder();
        for (int i = 0, il = arr.length; i < il; i++) {
            if (i > 0)
                sbStr.append(separator);
            sbStr.append(arr[i]);
        }
        return sbStr.toString();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void initializeCanvas(View view){

        DisplayMetrics metrics = new DisplayMetrics();
        getActivity().getWindowManager().getDefaultDisplay().getMetrics(metrics);

        drawingCanvas = view.findViewById(R.id.drawingCanvas);

        drawModel = new DrawModel(metrics.widthPixels, (int)((double)metrics.heightPixels*0.70));

        drawingCanvas.setModel(drawModel);
        drawingCanvas.setOnTouchListener(DoodleDrawingFragment.this);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouch(View v, MotionEvent event) {
        int action = event.getAction() & MotionEvent.ACTION_MASK;
        if (action == MotionEvent.ACTION_DOWN) {
            processTouchDown(event);
            return true;
        } else if (action == MotionEvent.ACTION_MOVE) {
            processTouchMove(event);
            return true;
        } else if (action == MotionEvent.ACTION_UP) {
            processTouchUp();
            return true;
        }
        return false;
    }

    private void processTouchDown(MotionEvent event) {
        mLastX = event.getX();
        mLastY = event.getY();
        drawingCanvas.calcPos(mLastX, mLastY, mTmpPoint);
        float lastConvX = mTmpPoint.x;
        float lastConvY = mTmpPoint.y;
        drawModel.startLine(lastConvX, lastConvY);
    }

    private void processTouchMove(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();

        drawingCanvas.calcPos(x, y, mTmpPoint);
        float newConvX = mTmpPoint.x;
        float newConvY = mTmpPoint.y;
        drawModel.addLineElem(newConvX, newConvY);

        mLastX = x;
        mLastY = y;
        drawingCanvas.invalidate();
    }

    private void processTouchUp() {
        drawModel.endLine();

        // try to predict drawing after the user has lifted his/her finger
        if(!stopPredicting)
            predictor.predict(drawingCanvas.getBitmap(),
                    NUMBER_OF_TOP_PREDICTIONS /* number of top predictions to fetch*/ );
    }

    @Override
    public void onResume() {
        super.onResume();
        drawingCanvas.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();
        drawingCanvas.onPause();
    }

    // todo: do something else here
    public void onBackPressed(){
        Toast.makeText(getContext(),
                "Please use the close button", Toast.LENGTH_SHORT).show();
    }

}