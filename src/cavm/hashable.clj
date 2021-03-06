(ns
  ^{:author "Brian Craft"
    :doc "Hashable byte array type."}
  cavm.hashable)

(deftype AHashable [ba] 
  Object 
  (equals [this other] 
    (if (instance? AHashable other) 
      (java.util.Arrays/equals ^bytes ba ^bytes (.ba ^AHashable other)) 
      false)) 
  (hashCode [this] 
    (java.util.Arrays/hashCode ^bytes ba)))

(defn ahashable [ba]
  (AHashable. ba))

(defn get-array [^AHashable ah]
  (.ba ah))
