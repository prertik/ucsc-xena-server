(ns cavm.benchmark.core
  (:require [clojure.java.io :as io])
  (:require [clojure.string :as s])
  (:require [cavm.benchmark.data :as benchmark-data])
  (:require [cavm.benchmark.install :refer [install]])
  (:require [cavm.h2 :as h2])
  (:require [cavm.db :as cdb]))

; This should be fine for long-running tasks. As tasks become shorter
; hotspot effects become important, and a tool like criterium is required.
(defmacro timeit [expr]
  `(let [start# (. System (nanoTime))]
     ~expr
     (/ (double (- (. System (nanoTime)) start#)) 1000000.0)))

(defn spy [msg x]
  (println msg x)
  x)

(def dataset-map
  (into {} (map (fn [ds] [(:key ds) ds]) benchmark-data/datasets)))

; XXX should probably validate the return values to some degree, so
; that we can see nothing is broken. Or do that in unit tests?
(defn probemap-probe [xena {id :id} probes]
  (timeit (cdb/column-query xena
                            {:select ["name" "position"]
                             :from [id]
                             :where [:in "name" probes]})))

(defn cnv-gene [xena {id :id} genes]
  (timeit (cdb/column-query xena
                            {:select ["position" "sampleID" "value"]
                             :from [id]
                             :where [:in "position" (mapv (juxt :chr :start :end) genes)]})))

(defn sort-genes [genes]
  (sort (fn [x y]
          (compare [(:chr x) (:start x)]
                   [(:chr y) (:start y)])) genes))

(def dbfile
  (str (io/file (System/getProperty "user.home") "xena-benchmark/database")))

(defn datafile [id]
  (str (io/file (System/getProperty "user.home") "xena-benchmark/files" id)))

(defn report [msg t]
  (format "%60s\t%f msec" msg t))


(defn line-count [file]
  (with-open [rdr (clojure.java.io/reader file)]
    (count (line-seq rdr))))

(defn rnd-lines [lines size n]
  (let [select (set (take n (distinct (repeatedly #(rand-int size)))))]
    (take n (filter identity
                    (map-indexed (fn [idx itm] (when (select idx) itm))
                                 lines)))))

; cut fields from tsv, similar to unix 'cut'
(defn cut [fields lines]
  (map (fn [line]
         (let [cols (s/split line #"\t")]
           (mapv #(cols %) fields)))
       lines))

; get probes from probemap
(defn get-probes [{id :id} c]
  (let [file (datafile id)
        N (line-count file)]
    (with-open [rdr (clojure.java.io/reader file)]
      (doall (flatten (cut [0] (rnd-lines (drop 1 (line-seq rdr)) (dec N) c)))))))

(defn gene-line [[chr start end gene]]
  {:chr chr
   :start (Integer/parseInt (s/trim start))
   :end (Integer/parseInt (s/trim end))
   :gene gene})

; get genes from refseq, by position.
; Note this will fail if the column order is different.
(defn get-genes [{id :id} c]
  (let [file (datafile id)
        N (line-count file)]
    (with-open [rdr (clojure.java.io/reader file)]
      (doall (map gene-line
                  (cut [2 4 5 12] (rnd-lines (drop 1 (line-seq rdr)) (dec N) c)))))))

(def tests
  [^:probemap
   {:desc "1 probe from probemap of 1.4M rows"
    :id :probemap-large-one
    :params (fn []
            [(get-probes (dataset-map :probemap-large) 1)])
    :body (fn [xena probes]
            (probemap-probe xena (dataset-map :probemap-large) probes))}
   ^:probemap
   {:desc "2 probe from probemap of 1.4M rows"
    :id :probemap-large-two
    :params (fn []
            [(get-probes (dataset-map :probemap-large) 2)])
    :body (fn [xena probes]
            (probemap-probe xena (dataset-map :probemap-large) probes))}
   ^:cnv
   {:desc "3 gene positions from CNV of 970k rows"
    :id :cnv-large-three-gene
    :params (fn []
            [(get-genes (dataset-map :refgene-hg19) 3)])
    :body (fn [xena genes]
            (cnv-gene xena (dataset-map :cnv-large) genes))}
   ^:cnv
   {:desc "30 gene positions from CNV of 970k rows"
    :id :cnv-large-thirty-gene
    :params (fn []
            [(get-genes (dataset-map :refgene-hg19) 30)])
    :body (fn [xena genes]
            (cnv-gene xena (dataset-map :cnv-large) genes))}
   ^:cnv
   {:desc "300 gene positions from CNV of 970k rows"
    :id :cnv-large-three-hundred-gene
    :params (fn []
            [(get-genes (dataset-map :refgene-hg19) 300)])
    :body (fn [xena genes]
            (cnv-gene xena (dataset-map :cnv-large) genes))}])

(defn -main [& args]
  (println "benchmark" args)

  (try
    (if (= args ["install"])
      (install (io/file (System/getProperty "user.home") "xena-benchmark"))
      (let [xena (h2/create-xenadb dbfile)
            {:keys [id probes]} (first benchmark-data/probemaps)
            {cnv-id :id} (first benchmark-data/cnv)]
        (doseq [{:keys [desc params body]} tests]
          (let [params (params)
                res (apply body xena params)]
            (println (report desc res))))
        ))
    (finally
      (shutdown-agents))))