/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package sampling.likelihood;

import java.util.ArrayList;
import java.util.HashMap;
import sampling.AbstractDiscreteFiniteLikelihoodModel;
import util.SamplerUtils;

/**
 * Implementation of a multinomial likelihood model in which the multinomial
 * distribution is integrated out
 *
 * @author vietan
 */
public class DirichletMultinomialModel extends AbstractDiscreteFiniteLikelihoodModel {
    // prior

    private double concentration; // concentration parameter
    private double[] center; // the mean vector for asymmetric distribution
    private double centerElement; // an element in the mean vector for symmetric distribution
    private double[] distribution;

    public DirichletMultinomialModel(int dim, double concentration, double centerElement) {
        super(dim);
        this.centerElement = centerElement;
        this.concentration = concentration;
    }
    /*TODO: dim can be inferred from the dimension of centerVector. remove the
     * argument "dim"! */

    public DirichletMultinomialModel(int dim, double concentration, double[] centerVector) {
        super(dim);
        this.center = centerVector;
        this.concentration = concentration;
    }

    public DirichletMultinomialModel(double[] p) {
        super(p.length);
        this.concentration = 0.0;
        for (double v : p) {
            this.concentration += v;
        }
        this.center = new double[dimension];
        for (int i = 0; i < dimension; i++) {
            this.center[i] = p[i] / this.concentration;
        }
    }

    public void setTrueDistribution(double[] dist) {
        this.distribution = dist;
    }

    public double[] getTrueDistribution() {
        return this.distribution;
    }

    public void setHyperparameters(double[] p) {
        this.concentration = 0.0;
        for (double v : p) {
            this.concentration += v;
        }
        this.center = new double[dimension];
        for (int i = 0; i < dimension; i++) {
            this.center[i] = p[i] / this.concentration;
        }
    }

    public void setConcentration(double conc) {
        this.concentration = conc;
    }

    public void setCenterElement(double ce) {
        this.centerElement = ce;
    }

    public double getConcentration() {
        return concentration;
    }

    public double[] getCenterVector() {
        if (center == null) { // if this is null, this has a symmetric Dirichlet prior
            center = new double[dimension];
            for (int i = 0; i < dimension; i++) {
                center[i] = 1.0 / dimension;
            }
        }
        return center;
    }

    public double getCenterElement(int index) {
        if (center == null) {
            return centerElement;
        }
        return this.center[index];
    }

    @Override
    public String getModelName() {
        return "Dirichlet-Multinomial";
    }

    @Override
    public void sampleFromPrior() {
        // Do nothing here since in this case, we integrate over all possible 
        // multinomials due to the conjugacy betwen Dirichlet and multinomial
        // distributions.
//        throw new RuntimeException(DirichletMultinomialModel.class 
//                + " is not currently supporting sampling from prior since the "
//                + "multinomial is integrated out.");
    }

    @Override
    public DirichletMultinomialModel clone() throws CloneNotSupportedException {
        DirichletMultinomialModel newMult = (DirichletMultinomialModel) super.clone();
        if (!isShortRepresented()) {
            newMult.center = (double[]) this.center.clone();
        }
        return newMult;
    }

    public double getLogLikelihood(ArrayList<Integer> observations) {
        HashMap<Integer, Integer> observationMap = new HashMap<Integer, Integer>();
        for (int obs : observations) {
            Integer count = observationMap.get(obs);
            if (count == null) {
                observationMap.put(obs, 1);
            } else {
                observationMap.put(obs, count + 1);
            }
        }
        return getLogLikelihood(observationMap);
    }

    public double getLogLikelihood(HashMap<Integer, Integer> observations) {
        double llh = 0.0;
        int j = 0;
        for (int observation : observations.keySet()) {
            for (int i = 0; i < observations.get(observation); i++) {
                llh += Math.log(concentration * getCenterElement(observation) + getCount(observation) + i)
                        - Math.log(concentration + getCountSum() + j);
                j++;
            }
        }
        return llh;
    }

    @Override
    public double getLogLikelihood(int observation) {
        double prior;
        if (isShortRepresented()) {
            prior = this.centerElement * this.concentration;
        } else {
            prior = this.center[observation] * this.concentration;
        }
        return Math.log(this.getCount(observation) + prior)
                - Math.log(this.getCountSum() + this.concentration);
    }

    @Override
    public double getLogLikelihood() {
        if (isShortRepresented()) {
            return SamplerUtils.computeLogLhood(getCounts(), getCountSum(), centerElement * concentration);
        } else {
            double[] params = new double[this.getDimension()];
            for (int i = 0; i < this.getDimension(); i++) {
                params[i] = center[i] * concentration;
            }
            return SamplerUtils.computeLogLhood(getCounts(), getCountSum(), params);
        }
    }

    public double getLogLikelihood(double[] params) {
        return SamplerUtils.computeLogLhood(getCounts(), getCountSum(), params);
    }

    public double getLogLikelihood(double concentr, double centerE) {
        return SamplerUtils.computeLogLhood(getCounts(), getCountSum(), centerE * concentr);
    }

    public double getLogLikelihood(double concentr, double[] centerV) {
        double[] params = new double[this.getDimension()];
        for (int i = 0; i < this.getDimension(); i++) {
            params[i] = centerV[i] * concentr;
        }
        return SamplerUtils.computeLogLhood(getCounts(), getCountSum(), params);
    }

    @Override
    public double[] getDistribution() {
        double[] distr = new double[getDimension()];
        for (int k = 0; k < distr.length; k++) {
            if (isShortRepresented()) {
                distr[k] = (getCount(k) + concentration * centerElement) / (getCountSum() + concentration);
            } else {
                distr[k] = (getCount(k) + concentration * center[k]) / (getCountSum() + concentration);
            }
        }
        return distr;
    }

    public double[] getEmpiricalDistribution() {
        double[] empDist = new double[getDimension()];
        for (int k = 0; k < empDist.length; k++) {
            empDist[k] = (double) getCount(k) / getCountSum();
        }
        return empDist;
    }

    /**
     * Return true if the Dirichlet is short-represented by a scalar, indicating
     * a symmetric Dirichlet distribution.
     */
    public boolean isShortRepresented() {
        return this.center == null;
    }

    @Override
    public String getDebugString() {
        StringBuilder str = new StringBuilder();
        str.append("Dimension = ").append(this.dimension).append("\n");
        str.append("Count sum = ").append(this.getCountSum()).append("\n");
        str.append("Counts = ").append(java.util.Arrays.toString(this.getCounts())).append("\n");
        str.append("Concentration = ").append(this.concentration).append("\n");
        str.append("Short-represented = ").append(isShortRepresented()).append("\n");
        if (isShortRepresented()) {
            str.append("Mean element = ").append(this.centerElement).append("\n");
        } else {
            str.append("Mean vector = ").append(java.util.Arrays.toString(this.center)).append("\n");
        }
        return str.toString();
    }

    @Override
    public String toString() {
        StringBuilder str = new StringBuilder();
        str.append("Dimension = ").append(this.dimension).append("\n");
        str.append("Count sum = ").append(this.getCountSum()).append("\n");
        return str.toString();
    }

    public static String output(DirichletMultinomialModel model) {
        StringBuilder str = new StringBuilder();
        str.append(model.dimension)
                .append("\t").append(model.concentration);
        for (int v = 0; v < model.dimension; v++) {
            str.append("\t").append(model.getCenterElement(v));
        }
        for (int v = 0; v < model.dimension; v++) {
            str.append("\t").append(model.getCount(v));
        }
        return str.toString();
    }

    public static DirichletMultinomialModel input(String str) {
        String[] sline = str.split("\t");
        int dim = Integer.parseInt(sline[0]);
        double concentration = Double.parseDouble(sline[1]);
        double[] mean = new double[dim];
        int idx = 2;
        for (int v = 0; v < dim; v++) {
            mean[v] = Double.parseDouble(sline[idx++]);
        }
        DirichletMultinomialModel model = new DirichletMultinomialModel(dim, concentration, mean);
        for (int v = 0; v < dim; v++) {
            model.changeCount(v, Integer.parseInt(sline[idx++]));
        }
        return model;
    }

    public static void main(String[] args) {
        try {
//            testClone();

//            testPrior();

            testLlh();
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void testLlh() throws Exception {
        double[] mean = {0.5, 0.5};
        double scale = 2;
        DirichletMultinomialModel dmm = new DirichletMultinomialModel(mean.length, scale, mean);
        System.out.println(dmm.getLogLikelihood(0));
        dmm.increment(0);
        System.out.println(dmm.getLogLikelihood());

        System.out.println(dmm.getDebugString());
        System.out.println(dmm.getLogLikelihood(1));
        dmm.increment(1);
        System.out.println(dmm.getDebugString());
        System.out.println(dmm.getLogLikelihood());
    }

    private static void testPrior() throws Exception {
        double[] mean = {0.7, 0.2, 0.1};
        double conc = 10000;
        DirichletMultinomialModel mm = new DirichletMultinomialModel(mean.length, conc, mean);

        mm.increment(0);
        System.out.println(java.util.Arrays.toString(mm.getDistribution()));
    }

    private static void testClone() throws Exception {
        int dim = 10;
        double con = 0.5;
        double ce = 0.1;
        DirichletMultinomialModel mm = new DirichletMultinomialModel(dim, con, ce);
        mm.increment(1);

        DirichletMultinomialModel newMM = (DirichletMultinomialModel) mm.clone();
        System.out.println(mm.getDebugString());
        System.out.println(newMM.getDebugString());

        mm.increment(0);
        newMM.concentration = 10;

        System.out.println(mm.getDebugString());
        System.out.println(newMM.getDebugString());
    }
}
