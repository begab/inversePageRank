package hu.u_szeged.experiments;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.zip.GZIPInputStream;

import hu.u_szeged.graph.PRWeightLearner;
import hu.u_szeged.graph.PageRankCalculator;
import hu.u_szeged.utils.Utils;

public class KosarakExperiment extends AbstractExperiment {

  private Map<Integer, Integer> outLinks;
  private Map<Integer, Map<Integer, Double>> etalonTransitions;
  private double[] pagerank;

  public KosarakExperiment(String file, double teleportProb) {
    super(file);
    init(teleportProb);
    learner.setRegularization(0, PRWeightLearner.RegularizationType.ORACLE);
  }

  @Override
  protected void readNodes(String inFile) {
    etalonTransitions = new HashMap<>();

    try (BufferedReader br = new BufferedReader(new InputStreamReader(new GZIPInputStream(new FileInputStream(inFile))))) {
      String line;
      while ((line = br.readLine()) != null) {
        String parts[] = line.split(" ");
        if (parts.length > 1) {
          for (String label : parts) {
            if (!labelsToIds.containsKey(label)) {
              Integer id = labelsToIds.size();
              labelsToIds.putIfAbsent(label, id);
              etalonTransitions.putIfAbsent(id, new HashMap<>());
            }
          }
        }
      }
    } catch (IOException ie) {
      ie.printStackTrace();
    }
  }

  @Override
  protected void readEdges(String infile) {
    outLinks = new HashMap<>();
    try (BufferedReader br = new BufferedReader(new InputStreamReader(new GZIPInputStream(new FileInputStream(infile))))) {
      String line;
      while ((line = br.readLine()) != null) {
        String parts[] = line.split(" ");
        for (int i = 1; i < parts.length; ++i) {
          Integer from = labelsToIds.get(parts[i - 1]);
          Integer to = labelsToIds.get(parts[i]);
          outLinks.put(from, outLinks.getOrDefault(from, 0) + 1);
          etalonTransitions.get(from).put(to, etalonTransitions.get(from).getOrDefault(to, 0.) + 1);
          g.addEdge(from, to, 1);
        }
      }
    } catch (IOException ie) {
      ie.printStackTrace();
    }
  }

  public void determineEtalon() {
    etalonDistr = new double[g.getNumOfNodes()];
    double totalWeights = 0.0;
    for (int i = 0; i < g.getNumOfNodes(); ++i) {
      int[] neighbors = g.getOutLinks(i);
      double[] weights = g.getWeights(i);
      for (int j = 1; j <= neighbors[0]; ++j) {
        totalWeights += weights[j];
        etalonDistr[neighbors[j]] += weights[j];
      }
    }
    System.err.println(totalWeights);
    for (int i = 0; i < g.getNumOfNodes(); ++i) {
      etalonDistr[i] /= totalWeights;
    }
    try (PrintWriter out = new PrintWriter("kosarak_edges_adapted.tsv")) {
      for (int i = 0; i < g.getNumOfNodes(); ++i) {
        int[] neigs = g.getOutLinks(i);
        for (int j = 1; j <= neigs[0]; ++j) {
          out.format("%d\t%d\t%.15f\n", i, neigs[j], etalonDistr[neigs[j]]);
        }
      }
    } catch (IOException io) {
      io.printStackTrace();
    }
    PageRankCalculator prc = new PageRankCalculator();
    pagerank = prc.calculatePageRank(g, false);
  }

  private double[] evaluateNode(double[] ws, int[] ns, int nodeId) {
    Map<Integer, Double> transitions = etalonTransitions.get(nodeId);
    double predictedNormalizer = 0;
    double etalonNormalizer = outLinks.get(nodeId), max = 0;
    double[] etalonDistr = new double[ns[0] + 1];
    double[] predictedDistr = new double[ns[0] + 1];
    int argMaxNeighbor = -1, argmaxRank = -1;
    for (int i = 1; i <= ns[0]; ++i) {
      etalonDistr[i] = transitions.get(ns[i]) / etalonNormalizer;
      if (etalonDistr[i] > max) {
        max = etalonDistr[i];
        argMaxNeighbor = ns[i];
      }
      predictedNormalizer += ws[i];
    }
    for (int i = 1; predictedNormalizer > 0 && i <= ns[0]; ++i) {
      predictedDistr[i] = ws[i] / predictedNormalizer;
    }

    int[] sort = Utils.stableSort(predictedDistr);
    Map<Integer, double[]> sortedPredictions = new HashMap<>();
    for (int i = sort.length - 1, rank = 0; i >= 0; --i) {
      if (sort[i] != 0) {
        sortedPredictions.put(ns[sort[i]], new double[] { ++rank, predictedDistr[sort[i]] });
        if (ns[sort[i]] == argMaxNeighbor) {
          argmaxRank = rank;
        }
      }
    }

    sort = Utils.stableSort(etalonDistr);
    Map<Integer, double[]> sortedEtalons = new HashMap<>();
    for (int i = sort.length - 1, rank = 0; i >= 0; --i) {
      if (sort[i] != 0) {
        sortedEtalons.put(ns[sort[i]], new double[] { ++rank, etalonDistr[sort[i]] });
      }
    }

    double kl = 0, rmse = 0, rankDisplacement = 0;
    for (Entry<Integer, double[]> etalon : sortedEtalons.entrySet()) {
      double[] preds = sortedPredictions.get(etalon.getKey());
      double etalonProb = etalon.getValue()[1];
      rankDisplacement += Math.abs(etalon.getValue()[0] - preds[0]);
      if (etalonProb > 0) {
        kl += etalonProb * Math.log(etalonProb / preds[1]);
        rmse += Math.pow(etalonProb - preds[1], 2.0);
      }
    }
    rmse = Math.sqrt(rmse / ns[0]);
    rankDisplacement /= Math.pow(ns[0], 2.0);
    double reciprocalRank = 1.0d / argmaxRank;
    return new double[] { kl, rmse, argmaxRank == 1 ? 1 : 0, reciprocalRank, rankDisplacement };
  }

  public static void main(String[] args) {
    try (PrintWriter out = new PrintWriter("kosarak.results")) {
      String[] evalMetrics = new String[] { "KL", "RMSE", "accuracy", "MRR", "displacement" };
      for (String m : evalMetrics) {
        out.write(m + "\t");
      }
      out.write("N\tmode\tteleport\tnum_models\n");
      for (double teleProb : new double[] { 0.2, 0.1, 0.05, 0.01 }) {
        for (int ri = 1; ri < 6; ++ri) { // number of random initializations to apply
          KosarakExperiment ke = new KosarakExperiment("kosarak.dat.gz", teleProb);
          ke.setVerbose(false);
          ke.learnWeights(ri, true);
          Random r = new Random(1);

          double[] choicerankParams = new double[ke.pagerank.length];
          try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream("kosarak_adapted_choicerank.params")))) {
            String line;
            int i = 0;
            while ((line = br.readLine()) != null) {
              if (i < choicerankParams.length) {
                choicerankParams[i++] = Math.exp(Double.parseDouble(line));
              }
            }
          } catch (IOException e) {
            e.printStackTrace();
          }

          System.err.println("==========" + teleProb + " " + ri);
          for (String mode : new String[] { "uniform", "indegree", "jaccard", "popularity", "pagerank", "prlearn", "choicerank" }) {
            // for (String mode : new String[] { "prlearn" }) {
            double[] evals = new double[5];
            int counter = 0;
            for (int n = 0; n < ke.g.getNumOfNodes(); ++n) {
              int[] ns = ke.g.getOutLinks(n);
              if (ns[0] == 0) {
                continue;
              }
              counter++;
              double[] ws = new double[ns[0] + 1];
              if (mode.equals("jaccard")) {
                for (int i = 1; i <= ns[0]; ++i) {
                  int[] neighbors = ke.g.getOutLinks(ns[i]);
                  ws[i] = Utils.determineOverlap(ns, neighbors);
                  ws[i] /= (double) (ns[0] + neighbors[0] - ws[i]);
                }
              } else if (mode.equals("prlearn")) {
                ws = ke.g.getWeights(n);
              } else if (mode.equals("indegree")) {
                for (int i = 1; i <= ns[0]; ++i) {
                  ws[i] = ke.g.getIndegree(ns[i]);
                }
              } else if (mode.equals("uniform")) {
                for (int i = 1; i <= ns[0]; ++i) {
                  ws[i] = r.nextDouble();
                }
              } else if (mode.equals("popularity")) {
                for (int i = 1; i <= ns[0]; ++i) {
                  ws[i] = ke.etalonDistr[ns[i]];
                }
              } else if (mode.equals("pagerank")) {
                for (int i = 1; i <= ns[0]; ++i) {
                  ws[i] = ke.pagerank[ns[i]];
                }
              } else if (mode.contains("choicerank")) {
                for (int i = 1; i <= ns[0]; ++i) {
                  ws[i] = choicerankParams[ns[i]];
                }
              }
              if (mode.equals("prlearn") || (teleProb == 0.01 && ri == 5)) {
                double[] nodeEvals = ke.evaluateNode(ws, ns, n);
                for (int e = 0; e < evals.length; ++e) {
                  out.format("%.4f\t", nodeEvals[e]);
                  evals[e] += nodeEvals[e];
                }
                out.format("%d\t%s\t%.2f\t%d\n", ns[0], mode, teleProb, ri);
              }
            }
            for (int e = 0; e < evals.length; ++e) {
              evals[e] /= counter;
            }
            if (mode.equals("prlearn") || (teleProb == 0.01 && ri == 5)) {
              System.err.format("%s\t%.2f\t%d\t%s\t%d\t%d\n", mode, teleProb, ri, Arrays.toString(evals), counter, ke.g.getNumOfNodes());
            }
          }
        }
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
  }
}
