(ns apps.ml-hw-w2-nt
  (:require [clojure.tools.logging :as logging]
            [clojure.java.io :as io]
            [clojure.core.matrix :as m]
            [clojure.core.matrix.stats :as m-s]
            [clojure.tools.reader :as r]
            [apps.matrix :as a-m]))



(defn del-f-generator [x y]
  #(-> (m/mmul x %)
       (m/sub y)
       (m/mul 2)
       (m/broadcast [(count %) (m/row-count x)])
       m/transpose
       (m/mul x)
       m-s/mean
       (m/div 2)))

(defn del-2-f-generator [x y]
  (fn [theta]
    (-> x
        m/transpose
        (m/mmul x)
        (m/div (m/row-count x)))))

(as-> "homework_ml_week_2/ex1data2.txt" $
      (io/resource $)
      (io/reader $)
      (line-seq $)
      (map #(clojure.string/split % #",") $)
      (m/emap r/read-string $)
      (def ex1-data-2 $))

(defn compute-cost [x y theta]
  (-> (m/mmul x theta)
      (m/sub y)
      (m/pow 2)
      m-s/mean
      (/ 2)))

;; feature normalize
(defn fn-on-col [f m]
  (->> m
       m/transpose
       (map f)))

(defn feature-normalize [x]
  (-> x
      (m/sub (fn-on-col m-s/mean x))
      (m/div (fn-on-col m-s/sd x))))

(let [x-to-be-norm (m/select ex1-data-2 :all [0 1])
      y (m/select ex1-data-2 :all 2)
      x (m/join-along 1 (m/broadcast 1 [(count ex1-data-2) 1]) (feature-normalize x-to-be-norm))
      target-f (partial compute-cost x y)]

  (a-m/search-convergence-point target-f [1 1 1] :del-f (del-f-generator x y) :del-2-f (del-2-f-generator x y)))

(let [x-to-be-norm (m/select ex1-data-2 :all [0 1])
      y (m/select ex1-data-2 :all 2)
      x (m/join-along 1 (m/broadcast 1 [(count ex1-data-2) 1]) (feature-normalize x-to-be-norm))
      target-f (partial compute-cost x y)]

  (a-m/search-convergence-point target-f [1 1 1]))

