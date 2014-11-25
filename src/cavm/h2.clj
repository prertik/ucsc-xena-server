(ns
  ^{:author "Brian Craft"
    :doc "Xena H2 database backend"}
  cavm.h2
  (:require [clojure.java.jdbc.deprecated :as jdbcd])
  (:require [clojure.java.jdbc :as jdbc])
  (:require [org.clojars.smee.binary.core :as binary])
  (:require [clojure.java.io :as io])
  (:require [honeysql.core :as hsql])
  (:require [honeysql.format :as hsqlfmt])
  (:require [honeysql.types :as hsqltypes])
  (:require [clojure.data.json :as json])
  (:use [cavm.binner :only (calc-bin)])
  (:use [clj-time.format :only (formatter unparse)])
  (:use [cavm.hashable :only (ahashable)])
  (:require [me.raynes.fs :as fs])
  (:require [cavm.db :refer [XenaDb]])
  (:require [clojure.tools.logging :refer [spy trace info]])
  (:require [taoensso.timbre.profiling :refer [profile p]])
  (:require [clojure.core.cache :as cache])
  (:require [cavm.h2-unpack-rows :as unpack])
  (:require [cavm.statement :refer [sql-stmt sql-stmt-result cached-statement]])
  (:require [cavm.conn-pool :refer [pool]])
  (:require [cavm.lazy-utils :refer [consume-vec lazy-mapcat]])
  (:gen-class))

;
; Note that "bin" in this file is like a "segement" in the column store
; literature: a column is split into a series of segments (bins) before
; writing to disk (saving as blob in h2).
;

(def ^:dynamic ^:private *tmp-dir* (System/getProperty "java.io.tmpdir"))

(defn set-tmp-dir! [dir]
  (alter-var-root #'*tmp-dir* (constantly dir)))

; Coerce this sql fn name to a keyword so we can reference it.
(def ^:private KEY-ID (keyword "scope_identity()"))

(def ^:private float-size 4)
(def ^:private bin-size 1000)
(def ^:private score-size (* float-size bin-size))
; Add 30 for gzip header
;( def score-size (+ 30 (* float-size bin-size)))

; row count for batch operations (e.g. insert/delete)
(def ^:private batch-size 1000)

;
; Utility functions
;

(defn- chunked-pmap [f coll]
  (->> coll
       (partition-all 250)
       (pmap (fn [chunk] (doall (map f chunk))))
       (apply concat)))

(defn- memoize-key
  "Memoize with the given function of the arguments"
  [kf f]
  (let [mem (atom {})]
    (fn [& args]
      (let [k (kf args)]
        (if-let [e (find @mem k)]
          (val e)
          (let [ret (apply f args)]
            (swap! mem assoc k ret)
            ret))))))

;
; Table models
;

(declare score-decode)

(defn- cvt-scores [{scores :scores :as v}]
  (if scores
    (assoc v :scores (float-array (score-decode scores)))
    v))

;
; Table definitions.
;

; Note H2 has one int type: signed, 4 byte. Max is approximately 2 billion.
(def ^:private field-table
  ["CREATE SEQUENCE IF NOT EXISTS FIELD_IDS CACHE 2000"
   "CREATE TABLE IF NOT EXISTS `field` (
   `id` INT NOT NULL DEFAULT NEXT VALUE FOR FIELD_IDS PRIMARY KEY,
   `dataset_id` INT NOT NULL,
   `name` VARCHAR(255),
   UNIQUE(`dataset_id`, `name`),
   FOREIGN KEY (`dataset_id`) REFERENCES `dataset` (`id`) ON DELETE CASCADE)"])

(def ^:private field-score-table
  [(format  "CREATE TABLE IF NOT EXISTS `field_score` (
            `field_id` INT NOT NULL,
            `i` INT NOT NULL,
            `scores` VARBINARY(%d) NOT NULL,
            UNIQUE (`field_id`, `i`),
            FOREIGN KEY (`field_id`) REFERENCES `field` (`id`) ON DELETE CASCADE)" score-size)])

(def ^:private cohorts-table
  ["CREATE TABLE IF NOT EXISTS `cohorts` (
   `id` INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
   `name` VARCHAR(2000) NOT NULL UNIQUE)"])

; XXX What should max file name length be?
; XXX Unique columns? Indexes?
(def ^:private source-table
  ["CREATE TABLE IF NOT EXISTS `source` (
   `id` INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
   `name` VARCHAR(2000) NOT NULL,
   `time` TIMESTAMP NOT NULL,
   `hash` VARCHAR(40) NOT NULL)"])

(def ^:private dataset-table
  ["CREATE TABLE IF NOT EXISTS `dataset` (
   `id` INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
   `name` VARCHAR(1000) NOT NULL UNIQUE,
   `probeMap` VARCHAR(255),
   `shortTitle` VARCHAR(255),
   `longTitle` VARCHAR(255),
   `groupTitle` VARCHAR(255),
   `platform` VARCHAR(255),
   `cohort` VARCHAR(1000),
   `security` VARCHAR(255),
   `rows` INT,
   `status` VARCHAR(20),
   `text` VARCHAR (65535),
   `type` VARCHAR (255),
   `dataSubType` VARCHAR (255))"])

(def ^:private dataset-columns
  #{:name
    :probeMap
    :shortTitle
    :longTitle
    :groupTitle
    :platform
    :cohort
    :security
    :dataSubType
    :type
    :text})

(def ^:private dataset-defaults
  (into {} (map #(vector % nil) dataset-columns)))

(def ^:private dataset-source-table
  ["CREATE TABLE IF NOT EXISTS `dataset_source` (
   `dataset_id` INT NOT NULL,
   `source_id` INT NOT NULL,
   FOREIGN KEY (dataset_id) REFERENCES `dataset` (`id`) ON DELETE CASCADE,
   FOREIGN KEY (source_id) REFERENCES `source` (`id`) ON DELETE CASCADE)"])

(def ^:private dataset-meta
  {:defaults dataset-defaults
   :columns dataset-columns})

; XXX CASCADE might perform badly. Might need to do this incrementally in application code.
(def ^:private field-position-table
  ["CREATE TABLE IF NOT EXISTS `field_position` (
   `field_id` INT NOT NULL,
   `row` INT NOT NULL,
   `bin` INT,
   `chrom` VARCHAR(255) NOT NULL,
   `chromStart` INT NOT NULL,
   `chromEnd` INT NOT NULL,
   `strand` CHAR(1),
   FOREIGN KEY (`field_id`) REFERENCES `field` (`id`) ON DELETE CASCADE)"
   "CREATE INDEX IF NOT EXISTS chrom_bin ON field_position (`field_id`, `chrom`, `bin`)"
   "CREATE INDEX IF NOT EXISTS position_row ON field_position (`field_id`, `row`)"])

(def ^:private field-gene-table
  ["CREATE TABLE IF NOT EXISTS `field_gene` (
   `field_id` INT NOT NULL,
   `row` INT NOT NULL,
   `gene` VARCHAR(255) NOT NULL,
    FOREIGN KEY (`field_id`) REFERENCES `field` (`id`) ON DELETE CASCADE)"
   "CREATE INDEX IF NOT EXISTS field_gene ON `field_gene` (`field_id`, `gene`)"
   "CREATE INDEX IF NOT EXISTS gene_row ON `field_gene` (`field_id`, `row`)"])

;
; feature tables
;

(def ^:private feature-table
  ["CREATE SEQUENCE IF NOT EXISTS FEATURE_IDS CACHE 2000"
   "CREATE TABLE IF NOT EXISTS `feature` (
   `id` INT NOT NULL DEFAULT NEXT VALUE FOR FEATURE_IDS PRIMARY KEY,
   `field_id` INT(11) NOT NULL,
   `shortTitle` VARCHAR(255),
   `longTitle` VARCHAR(255),
   `priority` DOUBLE DEFAULT NULL,
   `valueType` VARCHAR(255) NOT NULL,
   `visibility` VARCHAR(255),
   FOREIGN KEY (`field_id`) REFERENCES `field` (`id`) ON DELETE CASCADE)"])

;
; blob unpacking
;

(def^:private  unpack-aliases
  ["CREATE ALIAS IF NOT EXISTS unpack FOR \"unpack_rows.unpack\""
   "CREATE ALIAS IF NOT EXISTS unpackCode FOR \"unpack_rows.unpackCode\""
   "CREATE ALIAS IF NOT EXISTS unpackValue FOR \"unpack_rows.unpackValue\""])

(def ^:private feature-columns
  #{:shortTitle
    :longTitle
    :priority
    :valueType
    :visibility})

(def ^:private feature-defaults
  (into {} (map #(vector % nil) feature-columns)))


(def ^:private feature-meta
  {:defaults feature-defaults
   :columns feature-columns})

; Order is important to avoid creating duplicate indexes. A foreign
; key constraint creates an index if one doesn't exist, so create our
; index before adding the constraint.
; XXX varchar size is pushing it. Do we need another type for clob-valued
; fields?
(def ^:private code-table
  ["CREATE TABLE IF NOT EXISTS `code` (
   `id` INT(11) NOT NULL AUTO_INCREMENT PRIMARY KEY,
   `field_id` INT(11) NOT NULL,
   `ordering` INT(10) unsigned NOT NULL,
   `value` VARCHAR(16384) NOT NULL,
   UNIQUE (`field_id`, `ordering`),
   FOREIGN KEY (`field_id`) REFERENCES `field` (`id`) ON DELETE CASCADE)"])

(defn- normalize-meta [m-ent metadata]
  (-> metadata
      (clojure.walk/keywordize-keys)
      (#(merge (:defaults m-ent) %))
      (select-keys (:columns m-ent))))

(defn- json-text [md]
  (assoc md :text (json/write-str md :escape-slash false)))

(defn- load-probe-meta
  [feature-seq field-id {:keys [order] :as feat}]
  (let [feature-id (feature-seq)
        fmeta (merge (normalize-meta feature-meta feat)
                     {:field_id field-id :id feature-id})]
    (cons [:insert-feature (assoc fmeta :id feature-id)]
          (for [[value ordering] order]
            [:insert-code {:field_id field-id
                           :ordering ordering
                           :value value}]))))
;
;
;

(defn do-command-while-updates [cmd]
  (while
    (> (first (jdbcd/do-commands cmd)) 0)))

(defn delete-rows-by-field-cmd [table field]
  (format "DELETE FROM `%s` WHERE `field_id` = %d LIMIT %d"
                table field batch-size))

(defn- clear-fields [exp]
  (jdbcd/with-query-results
    field
    ["SELECT `id` FROM `field` WHERE `dataset_id` = ?" exp]
    (doseq [{id :id} field]
      (doseq [table ["code" "feature" "field_gene" "field_position" "field_score"]]
        (do-command-while-updates
          (delete-rows-by-field-cmd table id)))))
  (do-command-while-updates
    (format "DELETE FROM `field` WHERE `dataset_id` = %d LIMIT %d"
            exp batch-size)))

; Deletes an experiment. We break this up into a series
; of commits so other threads are not blocked, and we
; don't time-out the db thread pool.
; XXX Might want to add a "deleting" status to the dataset table?
(defn- clear-by-exp [exp]
  (clear-fields exp)
  (jdbcd/delete-rows :field ["`dataset_id` = ?" exp]))

; Update meta entity record.
(defn- merge-m-ent [ename metadata]
  (let [normmeta (normalize-meta dataset-meta (json-text (assoc metadata
                                                                "name" ename)))
        {id :id} (jdbcd/with-query-results
                   row
                   [(str "SELECT `id` FROM `dataset` WHERE `name` = ?") ename]
                   (first row))]
    (if id
      (do
        (jdbcd/update-values :dataset ["`id` = ?" id] normmeta)
        id)
      (-> (jdbcd/insert-record :dataset normmeta) KEY-ID))))

;
; Hex
;

(def ^:private hex-chars (char-array "0123456789ABCDEF"))

(defn- bytes->hex [^bytes ba]
  (let [c (count ba)
        out ^chars (char-array (* 2 c))]
    (loop [i 0]
      (when (< i c)
        (aset out (* 2 i)
              (aget ^chars hex-chars
                    (bit-shift-right
                      (bit-and 0xF0 (aget ba i))
                      4)))
        (aset out (+ 1 (* 2 i))
              (aget ^chars hex-chars
                    (bit-and 0x0F (aget ba i))))
        (recur (inc i))))
    (String. out)))

;
; Binary buffer manipulation.
;

(defn- sort-float-bytes
  "Sorts a byte array by float byte order. This makes the bytes very slightly
  more compressable."
  [^bytes in]
  (let [c (count in)
        fc (/ c 4)
        out (byte-array c)]
    (loop [i 0
           b1 0
           b2 fc
           b3 (* fc 2)
           b4 (* fc 3)]
      (when (< i c)
        (aset out b1 (aget in i))
        (aset out b2 (aget in (+ i 1)))
        (aset out b3 (aget in (+ i 2)))
        (aset out b4 (aget in (+ i 3)))
        (recur (+ i 4) (inc b1) (inc b2) (inc b3) (inc b4))))
    out))

; Pick elements of float array, by index.
(defn- apick-float [^floats a idxs]
 (let [ia (float-array idxs)]
  (amap ia i ret (aget a i))))

(defn- ashuffle-float [mappings ^floats out ^floats in]
   (doseq [[i-in i-out] mappings]
     (aset out i-out (aget in i-in)))
   out)

(defn- bytes-to-floats [^bytes barr]
  (let [bb (java.nio.ByteBuffer/allocate (alength barr))
        fb (.asFloatBuffer bb)
        out (float-array (quot (alength barr) 4))]
    (.put bb barr)
    (.get fb out)
    out))

(defn- floats-to-bytes [^floats farr]
  (let [bb (java.nio.ByteBuffer/allocate (* (alength farr) 4))
        fb (.asFloatBuffer bb)]
    (.put fb farr)
    (.array bb)))

;
; Score encoders
;

(def ^:private codec-length
  (memoize (fn [len]
             (binary/repeated :float-le :length len))))

(defn- codec [blob]
  (codec-length (/ (count blob) float-size)))

(defn- score-decode [blob]
  (bytes-to-floats blob))

(defn- score-encode-orig [slist]
  (floats-to-bytes (float-array slist)))

(defn- score-encodez
  "Experimental compressing score encoder."
  [slist]
  (let [baos (java.io.ByteArrayOutputStream.)
        gzos (java.util.zip.GZIPOutputStream. baos)]
    (binary/encode (codec-length (count slist)) gzos slist)
    (.finish gzos)
    (.toByteArray baos)))

(defn- score-sort-encodez
  "Experimental compressing score encoder that sorts by byte order before compressing."
  [slist]
  (let [fbytes (score-encode-orig slist)
        ^bytes sorted (sort-float-bytes fbytes)
        baos (java.io.ByteArrayOutputStream.)
        gzos (java.util.zip.GZIPOutputStream. baos)]
    (.write gzos sorted)
    (.finish gzos)
    (.toByteArray baos)))

(defn- score-decodez [blob]
  (let [bais (java.io.ByteArrayInputStream. blob)
        gzis (java.util.zip.GZIPInputStream. bais)]
    (binary/decode (binary/repeated :float-le :length 10) gzis)))

(def ^:private score-encode score-encode-orig)

(defn- encode-row [encode row]
  (mapv encode (partition-all bin-size row)))

(defn- encode-fields [encoder matrix-fn]
  (chunked-pmap #(assoc % :data (encode-row encoder (:scores %)))
                (:fields (matrix-fn))))

;
; sequence utilities


; This is a stateful iterator. It may not be idiomatic in clojure, but it's a
; quick way to get sequential ids deep in a call stack. Maybe we should instead
; return a thunk that generates a seq, then zip with the data?

(defn- seq-iterator
  "Create iterator for a seq"
  [s]
  (let [a (atom s)]
    (fn []
      (let [s @a]
        (reset! a (next s))
        (first s)))))

; Generates primary keys using range, w/o accessing the db for each next value.
; This works in h2 when doing a csvread when the sequence has been created
; automatically by h2 with the column: h2 will update the sequence while reading
; the rows. It doesn't work if the sequence is create separately from the column.
; In that case h2 will not update the sequence after a csvread. We have to
; create the sequence separately in order to set the cache parameter.

(comment (defn- sequence-seq [seqname]
   (let [[{val :i}] (exec-raw (format "SELECT CURRVAL('%s') AS I" seqname) :results)]
     (range (+ val 1) Double/POSITIVE_INFINITY 1))))

(comment
  (defn- sequence-seq
    "Retrieve primary keys in batches by querying the db"
    [seqname]
    (let [cmd (format "SELECT NEXT VALUE FOR %s AS i FROM SYSTEM_RANGE(1, 100)" seqname)]
      (apply concat
             (repeatedly
               (fn [] (-> cmd str
                          (exec-raw :results)
                          (#(map :i %)))))))))

;
; Main loader routines. Here we build a sequence of vectors describing rows to be
; inserted, e.g.
; [:insert-score {:id 1 :scores #<byte[] [B@12345>}]
;

(defmulti ^:private load-field
  (fn [dataset-id field-id column]
    (-> column :valueType)))

(defmethod load-field :default [dataset-id field-id {:keys [field rows]}]
  (let [data (encode-row score-encode (force rows))]
    (conj (mapcat (fn [[block i]]
                    [[:insert-field-score {:field_id field-id
                                           :i i
                                           :scores block}]])
                  (map vector (consume-vec data) (range)))
          [:insert-field {:id field-id :dataset_id dataset-id :name field}])))

; This should be lazy to avoid simultaneously instantiating all the rows as
; individual objects.
(defmethod load-field "position" [dataset-id field-id {:keys [field rows]}]
  (conj (for [[position row] (map vector (force rows) (range))]
          [:insert-position (assoc position
                                   :field_id field-id
                                   :row row
                                   :bin (calc-bin
                                          (:chromStart position)
                                          (:chromEnd position)))])
        [:insert-field {:id field-id :dataset_id dataset-id :name field}]))

(defmethod load-field "genes" [dataset-id field-id {:keys [field rows row-val]}]
  (conj (mapcat (fn [[genes row]]
                  (for [gene (row-val genes)]
                    [:insert-gene {:field_id field-id :row row :gene gene}]))
                (map vector (consume-vec (force rows)) (range)))
        [:insert-field {:id field-id :dataset_id dataset-id :name field}]))

(defn- inferred-type
  "Replace the given type with the inferred type"
  [fmeta row]
  (assoc fmeta "valueType" (:valueType row)))

(defn- load-field-feature [feature-seq field-seq dataset-id field]
  (let [field-id (field-seq)]
    (concat (load-field dataset-id field-id field)
            (when-let [feature (force (:feature field))]
              (load-probe-meta feature-seq field-id (inferred-type feature field))))))
;
;
;
; Table writer that updates tables as rows are read.
;
;

(defn- quote-ent [x]
  (str \` (name x) \`))

(defn- insert-stmt ^cavm.statement.PStatement [table fields]
   (let [field-str (clojure.string/join ", " (map quote-ent fields))
         val-str (clojure.string/join ", " (repeat (count fields), "?"))
         stmt-str (format "INSERT INTO %s(%s) VALUES (%s)"
                      (quote-ent table) field-str val-str)]
     (sql-stmt (jdbcd/find-connection) stmt-str fields)))

(defn- sequence-stmt ^cavm.statement.PStatement [db-seq]
  (sql-stmt-result (jdbcd/find-connection)
                         (format "SELECT NEXT VALUE FOR %s AS i" db-seq)))

(defn- run-delays [m]
  (into {} (for [[k v] m] [k (force v)])))

(defn- table-writer-default [dir dataset-id matrix-fn]
  (with-open [code-stmt (insert-stmt :code [:value :id :ordering :field_id])
              position-stmt (insert-stmt :field_position
                                         [:field_id :row :bin :chrom :chromStart
                                          :chromEnd :strand])
              gene-stmt (insert-stmt :field_gene [:field_id :row :gene])
              feature-stmt (insert-stmt :feature [:id :field_id :shortTitle
                                                  :longTitle :priority
                                                  :valueType :visibility])
              field-stmt (insert-stmt :field [:id :dataset_id :name])
              field-score-stmt (insert-stmt :field_score [:field_id :scores :i])
              field-seq-stmt (sequence-stmt "FIELD_IDS")
              feature-seq-stmt (sequence-stmt "FEATURE_IDS")]
    ; XXX change these -seq functions to do queries returning vectors &
    ; simplify the destructuring. Or better, stop asking the db for ids and
    ; generate them ourselves. Getting ids is the slowest bit, even with a large
    ; cache.
    (let [feature-seq #(delay (-> (feature-seq-stmt []) first :i))
          field-seq #(delay (-> (field-seq-stmt []) first :i))
          ; XXX try memoization again?
          ;        score-id-fn (memoize-key (comp ahashable first) scores-seq)

          writer {:insert-field field-stmt
                  :insert-field-score field-score-stmt
                  :insert-position position-stmt
                  :insert-feature feature-stmt
                  :insert-code code-stmt
                  :insert-gene gene-stmt}
          row-count (atom 0)
          data (matrix-fn)
          warnings (meta data)
          inserts (lazy-mapcat #(do
                                  (swap! row-count max (count (force (:rows %))))
                                  (load-field-feature
                                    feature-seq
                                    field-seq
                                    dataset-id
                                    %))
                               data)]

      (doseq [insert-batch (partition-all batch-size inserts)]
        (jdbcd/transaction
          (doseq [[insert-type values] insert-batch]
            (when (= :insert-field insert-type)
              (trace "writing" (:name values)))
            ((writer insert-type) (run-delays values)))))
      {:rows @row-count
       :warnings warnings})))
;
;
; Table writer that updates one table at a time by writing to temporary files.
;
; Currently not functioning. Needs update for new schema.

(defn- insert-field-out [^java.io.BufferedWriter out seqfn dataset-id name]
  (let [field-id (seqfn)]
    (.write out (str field-id "\t" dataset-id "\t" name "\n"))
    field-id))

(defn- insert-scores-out [^java.io.BufferedWriter out seqfn slist]
  (let [score-id (seqfn)]
    (.write out (str score-id "\t" (:hex slist) "\n"))
    score-id))

(defn- insert-field-score-out [^java.io.BufferedWriter out field-id i score-id]
  (.write out (str field-id "\t" i "\t" score-id "\n")))

(comment
  (defn- read-from-tsv [table file & cols]
    (-> "INSERT INTO %s DIRECT SORTED SELECT * FROM CSVREAD('%s', '%s', 'fieldSeparator=\t')"
        (format table file (clojure.string/join "\t" cols))
        (exec-raw))))

(defn- tsv-encoder [scores]
  (let [s (score-encode scores)]
    {:scores s :hex (bytes->hex s)}))

(comment
  (defn- table-writer-csv [dir dataset-id matrix-fn]
    (let [field-seq (-> "FIELD_IDS" sequence-seq seq-iterator)
          scores-seq (-> "SCORES_IDS" sequence-seq seq-iterator)
          ffname (fs/file dir "field.tmp") ; XXX add pid to these, or otherwise make them unique
          sfname (fs/file dir "scores.tmp")
          fsfname (fs/file dir "field_score.tmp")

          fields (encode-fields tsv-encoder matrix-fn)]
      (try
        (let [field-meta (with-open
                           [field-file (io/writer ffname)
                            scores-file (io/writer sfname)
                            field-score-file (io/writer fsfname)]
                           (let [writer
                                 {:insert-field (partial
                                                  insert-field-out
                                                  field-file field-seq)
                                  :insert-score (memoize-key
                                                  #(ahashable (:scores (first %)))
                                                  #(insert-scores-out scores-file scores-seq %))
                                  :insert-field-score (partial
                                                        insert-field-score-out
                                                        field-score-file)}]
                             (reduce #(load-field-feature writer dataset-id %1 %2) '() fields)))]
          (read-from-tsv "field" ffname "ID" "DATASET_ID" "NAME")
          (read-from-tsv "scores" sfname "ID" "SCORES")
          (read-from-tsv "field_score" fsfname "FIELD_ID" "I" "SCORES_ID")
          (jdbcd/transaction
            (load-probe-meta field-meta)))
        (finally
          (fs/delete ffname)
          (fs/delete sfname)
          (fs/delete fsfname))))))

(def ^:private table-writer #'table-writer-default)

(let [fmtr (formatter "yyyy-MM-dd hh:mm:ss")]
  (defn- format-timestamp [timestamp]
    (unparse fmtr timestamp)))

(defn- fmt-time [file-meta]
  (assoc file-meta :time
         (. java.sql.Timestamp valueOf (format-timestamp (:time file-meta)))))

; XXX test this
; XXX  and call it somewhere.
(defn- clean-sources []
  (jdbcd/delete-rows :source
                     ["`id` NOT IN (SELECT DISTINCT `source_id` FROM `dataset_source)"]))

(defn- load-related-sources [table id-key id files]
  (jdbcd/delete-rows table [(str (name id-key) " = ?") id])
  (let [sources (map #(-> (jdbcd/insert-record :source %) KEY-ID) files)]
    (doseq [source-id sources]
      (jdbcd/insert-record table {id-key id :source_id source-id}))))

(defn- related-sources [id]
  (jdbcd/with-query-results rows
    ["SELECT `source`.`name`, `hash`, `time`
     FROM `dataset`
     JOIN `dataset_source` ON `dataset_id` = `dataset`.`id`
     JOIN `source` on `source_id` = `source`.`id`
     WHERE `dataset`.`id` = ?" id]
    (vec rows)))

(defn- with-load-status* [dataset-id func]
  (jdbcd/update-values :dataset ["`id` = ?" dataset-id] {:status "loading"})
  (try
    (func)
    (finally
      (jdbcd/update-values :dataset ["`id` = ?" dataset-id] {:status "loaded"}))))

(defmacro with-load-status [dataset-id & body]
  `(with-load-status* ~dataset-id (fn [] ~@body))) ; XXX need :^once?

; jdbcd/transaction will create a closure of our parameters,
; so we can't pass in the matrix seq w/o blowing the heap. We
; pass in a function to return the seq, instead.

; files: the files this matrix was loaded from, e.g. clinical & genomic matrix, plus their timestamps & file hashes
; metadata: the metadata for the matrix (should cgdata mix in the clinical metadata?)
; matrix-fn: function to return seq of data rows
; features: field metadata. Need a better name for this.

(defn- load-dataset
  "Load matrix file and metadata. Skips the data load if file hashes are unchanged,
  and 'force' is false. Metadata is always updated."
  ([mname files metadata matrix-fn features]
   (load-dataset mname files metadata matrix-fn features false))

  ([mname files metadata matrix-fn features force]
   (profile
     :trace
     :dataset-load
     (let [dataset-id (jdbcd/transaction (merge-m-ent mname metadata))
           files (map fmt-time files)]
       (with-load-status dataset-id
         (when (or force
                   (not (= (set files) (set (related-sources dataset-id)))))
           (jdbcd/transaction
             (p :dataset-clear
                (clear-by-exp dataset-id))
             (p :dataset-sources
                (load-related-sources
                  :dataset_source :dataset_id dataset-id files)))
           (p :dataset-table
              (let [{:keys [rows warnings]} (table-writer *tmp-dir* dataset-id matrix-fn)]
                (jdbcd/transaction
                  (jdbcd/update-values :dataset ["`id` = ?" dataset-id] {:rows rows})
                  (when warnings
                    (merge-m-ent mname (assoc metadata :loader warnings))))))))))))

(defn- dataset-by-name [dname]
  (jdbcd/with-query-results rows
    ["SELECT `id`
     FROM `dataset`
     WHERE `name` = ?" (str dname)]
    (-> rows first :id)))

(defn delete-dataset [dataset]
  (jdbcd/transaction
    (let [dataset-id (dataset-by-name dataset)]
      (if dataset-id
        (do
          (clear-by-exp dataset-id)
          (jdbcd/do-commands
            (format "DELETE FROM `dataset` WHERE `id` = %d" dataset-id)))
        (info "Did not delete unknown dataset" dataset)))))

(defn create-db [file & [{:keys [classname subprotocol make-pool?]
                          :or {classname "org.h2.Driver"
                               subprotocol "h2"
                               make-pool? true}}]]

  (let [spec {:classname classname
              :subprotocol subprotocol
              :subname file
              :conn-customizer "conn_customizer"
              :make-pool? make-pool?}]
        (if make-pool? (delay (pool spec)) (delay (-> spec)))))

; XXX should sanitize the query
(defn- run-query [q]
  (jdbcd/with-query-results rows (spy :trace (hsql/format q))
    (vec rows)))

;
; Code queries
;

(def ^:private codes-for-values-query
  (cached-statement
    "SELECT `ordering`, `value`
    FROM `field`
    JOIN `code` ON `field_id` = `field`.`id`
    WHERE  `name` = ? AND `dataset_id` = ?"
    true))

(defn- codes-for-values [dataset-id field]
  (->> (codes-for-values-query field dataset-id)
       (map #(mapv % [:value :ordering]))
       (into {})))

;
; All rows queries.
;

; All bins for the given fields.

(def ^:private all-rows-query
   (cached-statement
     "SELECT `name`, `i`, `scores`
     FROM (SELECT `name`, `id`
     FROM `field`
     INNER JOIN (TABLE(`name2` VARCHAR = ?) T) ON `name2` = `field`.`name`
     WHERE `dataset_id` = ?) AS P
     LEFT JOIN `field_score` ON `P`.`id` = `field_score`.`field_id`"
     true))

; {"foxm1" [{:name "foxm1" :scores <bytes>} ... ]
(let [concat-field-bins
      (fn [bins]
        (let [sorted (map :scores (sort-by :i bins))
              float-bins (map score-decode sorted)
              row-count (apply + (map count float-bins))
              out (float-array row-count)]
          (loop [bins float-bins offset 0]
            (let [b (first bins)
                  c (count b)]
              (when b
                (System/arraycopy (first bins) 0 out offset c) ; XXX mutate "out" in place
                (recur (rest bins) (+ offset c)))))
          out))]

  ; return float arrays of all rows for the given fields
  (defn- all-rows [dataset-id fields]
    (let [field-bins (->> (all-rows-query (to-array fields) dataset-id)
                          (group-by :name))]
      (into {} (for [[k v] field-bins] [k (concat-field-bins v)])))))

;
;
;

; merge bin number and bin offset for a row
(defn- merge-bin-off [{i :i :as row}]
  (merge row
         {:bin (quot i bin-size)
          :off (rem i bin-size)}))

; Returns mapping from input bin offset to output buffer for a
; given row.
(defn- bin-mapping [{off :off row :order}]
  [off row])


; Gets called once for each bin in the request.
; order is row -> order in output buffer.
; bin is the bin for the given set of rows
; rows is ({:i 12 :bin 1 :off 1}, ... ), where all :bin are the same
(defn- pick-rows-fn [[bin rows]]
  [bin (partial ashuffle-float
                (map bin-mapping rows))])

; Take an ordered list of requested rows, and generate fns to copy
; values from a score bin to an output array. Returns one function
; for each score bin in the request.
;
; rows ({:i 12 :bin 1 :off 1}, ... )
(defn- pick-rows-fns [rows]
  (let [by-bin (group-by :bin rows)]
    (apply hash-map (mapcat pick-rows-fn by-bin))))

; Take map of bin copy fns, list of scores rows, and a map of output arrays,
; copying the scores to the output arrays via the bin copy fns.
(defn- build-score-arrays [rows bfns out]
  (doseq [{i :i scores :scores field :name} rows]
    ((bfns i) (out field) scores))
  out)

(defn- col-arrays [columns n]
  (zipmap columns (repeatedly (partial float-array n Double/NaN))))

; Doing an inner join on a table literal, instead of a big IN clause, so it will hit
; the index.

; XXX performance of the :in clause?
; XXX make a prepared statement
; One would hope that an array could be passed in the for IN
; clause, since an array can be passed in elsewhere, but that
; doesn't appear to be the case. At least, I haven't found an
; incantation that works. h2 complains about the syntax. Maybe
; if it were written as a subquery? Would need to do an
; explain analyze if trying this. Meanwhile, we have to dynamically
; build the query to paste in the right number of ?.
(def ^:private dataset-fields-query
  "SELECT name, i, scores
   FROM (SELECT  `field`.`name`, `field`.`id`
         FROM `field`
         INNER JOIN TABLE(name varchar=?) T ON T.`name`=`field`.`name`
         WHERE (`field`.`dataset_id` = ?)) P
   LEFT JOIN `field_score` ON P.id = `field_score`.`field_id`
   WHERE `field_score`.`i` IN (%s)")

(defn- select-scores-full [dataset-id columns bins]
  (let [q (format dataset-fields-query
                  (clojure.string/join ","
                                       (repeat (count bins) "?")))
        c (to-array (map str columns))
        i bins]
    (jdbcd/with-query-results rows (vec (concat [q c dataset-id] i))
      (vec rows))))

; Returns rows from (dataset-id column-name) matching
; values. Returns a hashmap for each matching  row.
; { :i     The implicit index in the column as stored on disk.
;   ;order The order of the row the result set.
;   :value The value of the row.
; }
(defn- rows-matching [dataset-id column-name values]
  (let [val-set (set values)]
    (->> column-name
         (vector)
         (all-rows dataset-id)                 ; Retrieve the full column
         (vals)
         (first)
                                               ; calculate row index in the column
         (map #(-> {:i %1 :value %2}) (range)) ; [{:i 0 :value 1.2} ...]
         (filter (comp val-set :value))        ; filter rows not matching the request.
         (group-by :value)
         (#(map (fn [g] (get % g [nil])) values)) ; arrange by output order.
         (apply concat)                           ; flatten groups.
         (mapv #(assoc %2 :order %1) (range)))))  ; assoc row output order.

(defn- float-nil [x]
  (when x (float x)))

; Get codes for samples in request
; Get the entire sample column
; Find the rows matching the samples, ordered by sample list
;   Have to scan the sample column, group by sample, and map over the ordering
; Build shuffle functions, per bin, to copy bins to output buffer
; Run scores query
; Copy to output buffer

(defn- genomic-read-req [req]
  (let [{:keys [samples table columns]} req
        dataset-id (dataset-by-name table)
        samp->code (codes-for-values dataset-id "sampleID")
        order (mapv (comp float-nil samp->code) samples)     ; codes in request order
        rows (rows-matching dataset-id "sampleID" order)
        rows-to-copy (->> rows
                          (filter :value)
                          (mapv merge-bin-off))

        bins (mapv :bin rows-to-copy)        ; list of bins to pull.
        bfns (pick-rows-fns rows-to-copy)]   ; make fns that map from input bin to
                                             ; output buffer, one fn per input bin.

    (-> (select-scores-full dataset-id columns (distinct bins))
        (#(mapv cvt-scores %))
        (build-score-arrays bfns (col-arrays columns (count rows))))))

; Each req is a map of
;  :table "tablename"
;  :columns '("column1" "column2")
;  :samples '("sample1" "sample2")
; We merge into 'data the columns that we can resolve, like
;  :data { 'column1 [1.1 1.2] 'column2 [2.1 2.2] }
(defn- genomic-source [reqs]
  (map #(update-in % [:data] merge (genomic-read-req %)) reqs))

(defn- create[]
  (jdbcd/transaction
    (apply jdbcd/do-commands cohorts-table)
    (apply jdbcd/do-commands source-table)
    (apply jdbcd/do-commands dataset-table)
    (apply jdbcd/do-commands dataset-source-table)
    (apply jdbcd/do-commands field-table)
    (apply jdbcd/do-commands field-score-table)
    (apply jdbcd/do-commands feature-table)
    (apply jdbcd/do-commands code-table)
    (apply jdbcd/do-commands field-position-table)
    (apply jdbcd/do-commands field-gene-table)
    (apply jdbcd/do-commands unpack-aliases)))

;
; add TABLE handling for honeysql
;

(defmethod hsqlfmt/format-clause :table [[_ [fields alias]] _]
  (str "TABLE("
       (clojure.string/join ", "
       (map (fn [[name type values]]
              (str (hsqlfmt/to-sql name) " " (hsqlfmt/to-sql type) "="
                   (hsqlfmt/paren-wrap (clojure.string/join ", " (map hsqlfmt/to-sql values))))) fields))
       ") " (hsqlfmt/to-sql alias)))

; Fix GROUP_CONCAT in honeysql
(defn- format-concat
  ([value {:keys [order separator]}]
   (let [oclause (if (nil? order)
                   ""
                   (str " order by " (hsqlfmt/to-sql order)))
         sclause (if (nil? separator)
                   ""
                   (str " separator " (hsqlfmt/to-sql separator)))]
     (str "GROUP_CONCAT(" (hsqlfmt/to-sql value) oclause sclause ")"))))

(defmethod hsqlfmt/fn-handler "group_concat" [_ value & args]
  (format-concat value args))

(defrecord H2Db [db])

(extend-protocol XenaDb H2Db
  (write-matrix [this mname files metadata data-fn features always]
    (jdbcd/with-connection @(:db this)
      (load-dataset mname files metadata data-fn features always)))
  (delete-matrix [this mname]
    (jdbcd/with-connection @(:db this)
      (delete-dataset mname)))
  (run-query [this query]
    (jdbcd/with-connection @(:db this)
      (run-query query)))
  (fetch [this reqs]
    (jdbcd/with-connection @(:db this)
      (doall (genomic-source reqs))))
  (close [this] ; XXX test for pool, or datasource before running?
    (.close (:datasource @(:db this)) false)))

(defn create-xenadb [& args]
  (let [db (apply create-db args)]
    (jdbcd/with-connection @db (create))
    (H2Db. db)))

;
; Support for pulling a row from a blob column. Implements
; an LRU cache of retrieved blobs.
;

(def ^:private field-bin-query
  (cached-statement
    "SELECT `scores` FROM `field_score`
     WHERE `field_id` = ? AND `i` = ?"
    true))

(def ^:private feature-value-query
  (cached-statement
    "SELECT `value` FROM `code`
     WHERE `field_id` = ? AND `ordering` = ?"
    true))

(defn- fetch-bin [field-id bin]
  (when-let [scores (-> (field-bin-query field-id bin)
                        first
                        :scores)]
    (bytes-to-floats scores)))

(def ^:private cache-size (int (/ (* 128 1024) bin-size))) ; 128k bin cache

(def ^:private bin-cache-state
  (atom (cache/lru-cache-factory {} :threshold cache-size)))

(defn- update-bin-cache [c args]
  (if (cache/has? c args)
    (cache/hit c args)
    (cache/miss c args (apply fetch-bin args))))

(defn- bin-cache [& args]
  (cache/lookup (swap! bin-cache-state update-bin-cache args) args))

(defn- lookup-row [field-id row]
  (p :lookup-row
     (let [^floats bin (bin-cache field-id (quot row bin-size))
           i (rem row bin-size)]
       (when bin (aget bin i)))))

(defn- lookup-value [field-id row]
  (let [ordering (lookup-row field-id row)]
    (p :lookup-value
       (when ordering
         (when-let [value (feature-value-query field-id (int ordering))]
           (-> value first :value))))))

(unpack/set-lookup-row! lookup-row)
(unpack/set-lookup-value! lookup-value)
