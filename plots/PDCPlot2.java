import org.knowm.xchart.*;
import org.knowm.xchart.style.Styler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


public class PDCPlot2 { // Thread vs Avg Accuracy

    public static void main(String[] args) {
        // Number of threads to test
        // List<Integer> threadCounts = Arrays.asList(1, 2, 4, 8, 16, 32, 64, 96);
        List<Integer> threadCounts = Arrays.asList(1, 2, 4, 8, 16, 32, 48);

        // Distribution parameters: 10% add, 10% remove, 80% contains
        int[] unbalancedOps = {1, 1, 8};
        // Distribution parameters: 50% add, 50% remove, 0% contains
        int[] balancedOps = {1, 1, 0};

        // Number of operations per thread
        int opsPerThread = 1000000;

        // Distribution to sample values
        String distribution = "Uniform";
        // String distribution = "Normal";
        int maxValue = 100000;

        // Warm-up and measurement rounds
        int warmups = 5;
        int measurements = 10;

        // Results storage
        List<Double> unbalancedAccuracy = new ArrayList<>();
        List<Double> balancedAccuracy = new ArrayList<>();

        // Run experiments for both operation distributions
        runExperiments(threadCounts, "10% add, 10% remove, 80% contains",
                unbalancedOps, opsPerThread, distribution, maxValue, warmups, measurements, unbalancedAccuracy);
        runExperiments(threadCounts, "50% add, 50% remove",
                balancedOps, opsPerThread, distribution, maxValue, warmups, measurements, balancedAccuracy);

        // Plot the results using XChart
        plotResults(threadCounts, balancedAccuracy, unbalancedAccuracy);
    }

    // Function to run experiments with different thread counts and operation distribution
    public static void runExperiments(List<Integer> threadCounts, String operationDescription,
                                      int[] ops, int opsPerThread, String distribution,
                                      int maxValue, int warmups, int measurements,
                                      List<Double> AccuracyList) {

        for (int threads : threadCounts) {
            System.out.println("Running experiment with " + threads + " threads and " + operationDescription);

            // Prepare arguments for the experiment run
            String setName = "GlobalLog";  // Using Different LockFreeSet
            String[] args = {
                    Integer.toString(threads),  // Number of threads
                    setName,                    // Set type
                    distribution,               // Value distribution
                    Integer.toString(maxValue), // Max value
                    ops[0] + ":" + ops[1] + ":" + ops[2], // Operation distribution
                    Integer.toString(opsPerThread),  // Number of operations per thread
                    Integer.toString(warmups),       // Warmups
                    Integer.toString(measurements)   // Measurements
            };

            // Run the experiment for warmups and measurements
            double[] results = Main.run(args, false);
            AccuracyList.add(results[1]);
        }
    }

    // Function to plot the results using XChart
    public static void plotResults(List<Integer> threadCounts,
                                   List<Double> balancedAccuracy,
                                   List<Double> unbalancedAccuracy) {
        // Create the chart
        XYChart chart = new XYChartBuilder()
                .width(800).height(600)
                .title("Discrepancy vs Threads")
                .xAxisTitle("Threads")
                .yAxisTitle("Numbers")
                .build();

        // Customize the chart
        chart.getStyler().setDefaultSeriesRenderStyle(XYSeries.XYSeriesRenderStyle.Line);
        chart.getStyler().setChartTitleVisible(true);
        chart.getStyler().setLegendPosition(Styler.LegendPosition.InsideNW);
        chart.getStyler().setMarkerSize(6);

        // Add the data series
        chart.addSeries("50% add, 50% remove", threadCounts, balancedAccuracy);
        chart.addSeries("10% add, 10% remove, 80% contains", threadCounts, unbalancedAccuracy);

        // Save the chart to a PNG file
        String filename = "plots/plot2";
        try {
            BitmapEncoder.saveBitmap(chart, filename, BitmapEncoder.BitmapFormat.PNG);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            System.out.println("Chart saved to " + filename + ".png");
        }

        // Show the chart
        new SwingWrapper<>(chart).displayChart();
    }
}
