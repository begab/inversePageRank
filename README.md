# Supplementary code for the paper _Learning the transition matrix for the weighted inverse PageRank problem_

This repo includes the code for performing Inverse PageRank calculation.
In this setting the network topology and the importance of the nodes (i.e. the stationary distribution of a random walk) are assumed to be known in advance and algorithm looks for a weighting of the edges which ensures that the random walk visits each node proportional to the importance of the nodes provided in advance.

## The quickes way of trying out the programme (assuming Maven)
Assuming access to Maven, the quickest way to give the algorithm a try and also to see a visualization is to enter
```mvn exec:java``` in the command line.

## Dependencies
Our source code relies on the following dependencies (also included in the pom.xml for Maven.)
* Graphstream v1.3 (http://graphstream-project.org/download/)
* Mallet (http://mallet.cs.umass.edu/download.php)
