(ns cavm.readers)

;
; File format readers.
;
; Readers are split into two parts: a file type detector, and a reader multimethod.
; This split is maintained because there is a not a one-to-one relationship between
; detecting a file type and having a reader, e.g. we identify all cgdata file types
; with a single method that the reads file type from the json metadata.
;

(defmulti reader
  (fn [filetype file] filetype))

; XXX Note that this strategy opens each file repeatedly to determine its type.
(defn detector
  "Returns a function that invokes file type detectors in order
  until the file is identified, then returns the file type and its
  registered reader"
  [& detectors]
  (fn [file]
    (let [file-type (some #(% file) detectors)]
      {:file-type file-type
       :reader (delay (reader file-type file))})))
