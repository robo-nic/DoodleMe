package paul.cipherresfeber.doodleme.CNNModel;

import java.util.ArrayList;

import paul.cipherresfeber.doodleme.CustomData.LabelProbability;

public interface PredictionListener {
    public void PredictionCallback(ArrayList<LabelProbability> topPredictions);
}
