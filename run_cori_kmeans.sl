#!/bin/bash
#SBATCH -p regular
#SBATCH -N 6
#SBATCH -C haswell
#SBATCH -t 00:20:00
#SBATCH -J alchemist_kmeans
#SBATCH -L SCRATCH
#SBATCH -e alchemist_kmeans_job_%j.err
#SBATCH -o alchemist_kmeans_job_%j.out

module load spark
ulimit -s unlimited
start-all.sh

spark-submit --driver-memory 5g --executor-memory 5g --num-executors 2 test/target/scala-2.11/alchemist-tests-assembly-0.0.2.jar 2>&1

stop-all.sh
