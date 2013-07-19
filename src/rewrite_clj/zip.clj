(ns ^{ :doc "Zipper Utilities for EDN Trees." 
       :author "Yannick Scherer" } 
  rewrite-clj.zip
  (:refer-clojure :exclude [replace next remove find])
  (:require [fast-zip.core :as z]
            [rewrite-clj.convert :as conv]))

;; ## Access

(defn tag
  "Get tag of structure at current zipper location."
  [zloc]
  (when zloc
    (first (z/node zloc))))

(defn value
  "Get the first child of the current zipper node."
  [zloc]
  (when zloc
    (second (z/node zloc))))

(defn sexpr
  "Get the s-expression of the current node."
  [zloc]
  (when zloc
    (conv/->sexpr (z/node zloc))))

(defn whitespace?
  "Check if the node at the current zipper location is whitespae or comment."
  [zloc]
  (contains? #{:comment :whitespace} (tag zloc)))

;; ## Zipper

(defn- z-branch?
  [node]
  (when (vector? node)
    (let [[k & _] node]
      (contains? #{:list :vector :set :map} k))))

(defn- z-make-node
  [node ch]
  (apply vector (first node) ch))

(def edn
  "Create zipper over rewrite-clj's EDN tree structure."
  (partial z/zipper z-branch? rest z-make-node))

;; ## Skip

(defn skip
  "Skip locations that match the given predicate by applying the given movement function
   to the initial zipper location."
  [f p? zloc]
  (->> zloc
    (iterate f)
    (take-while identity)
    (drop-while p?)
    (first)))

(defn skip-whitespace
  "Apply movement function (default: `clojure.z/right`) until a non-whitespace/non-comment
   element is reached."
  ([zloc] (skip-whitespace z/right zloc))
  ([f zloc] (skip f whitespace? zloc)))

(defn skip-whitespace-left
  "Move left until a non-whitespace/non-comment element is reached."
  [zloc]
  (skip-whitespace z/left zloc))

;; ## Whitespace-sensitive Zipper Wrappers

(def ^:private ^:const SPACE [:whitespace " "])

;; ### Move

(def right 
  "Move right to next non-whitespace/non-comment location."
  (comp skip-whitespace z/right))

(def left 
  "Move left to next non-whitespace/non-comment location."
  (comp skip-whitespace-left z/left))

(def down
  "Move down to next non-whitespace/non-comment location."
  (comp skip-whitespace z/down))

(def up 
  "Move up to next non-whitespace/non-comment location."
  (comp skip-whitespace-left z/up))

(def next
  "Move to the next non-whitespace/non-comment location in a depth-first manner."
  (comp skip-whitespace z/next))

(def prev 
  "Move to the next non-whitespace/non-comment location in a depth-first manner."
  (comp skip-whitespace-left z/prev))

(def leftmost 
  "Move to the leftmost non-whitespace/non-comment location."
  (comp skip-whitespace z/leftmost))

(def rightmost 
  "Move to the rightmost non-whitespace/non-comment location."
  (comp skip-whitespace-left z/rightmost))

;; ### Insert

(defn insert-right
  "Insert item to the right of the current location. Will insert a space if necessary."
  [zloc item]
  (let [r (-> zloc z/right)]
    (cond (not (z/node zloc)) (-> zloc (z/replace item))
          (or (not r) (whitespace? r)) (-> zloc (z/insert-right item) (z/insert-right SPACE))
          :else (-> zloc (z/insert-right SPACE) (z/insert-right item) (z/insert-right SPACE)))))

(defn insert-left
  "Insert item to the left of the current location. Will insert a space if necessary."
  [zloc item]
  (let [r (-> zloc z/left)]
    (cond (not (z/node zloc)) (-> zloc (z/replace item))
          (or (not r) (whitespace? r)) (-> zloc (z/insert-left item) (z/insert-left SPACE))
          :else (-> zloc (z/insert-left SPACE) (z/insert-left item) (z/insert-left SPACE)))))

(defn insert-child
  "Insert item as child of the current location. Will insert a space if necessary."
  [zloc item]
  (let [r (-> zloc z/down)]
    (if (or (not r) (not (z/node r)) (whitespace? r))
      (-> zloc (z/insert-child item))
      (-> zloc (z/insert-child SPACE) (z/insert-child item)))))

(defn append-child
  "Append item as child of the current location. Will insert a space if necessary."
  [zloc item]
  (let [r (-> zloc z/down z/rightmost)]
    (if (or (not r) (not (z/node r)) (whitespace? r))
      (-> zloc (z/append-child item))
      (-> zloc (z/append-child SPACE) (z/append-child item)))))

;; ## Modify

(defn replace
  "Replace value at the given zipper location with the given value."
  [zloc v]
  (z/replace zloc (conv/->tree v)))

(defn edit
  "Replace value at the given zipper location with value of (f node-sexpr args)."
  [zloc f & args]
  (let [form (sexpr zloc)]
    (z/replace zloc (conv/->tree (apply f form args)))))

;; ## Others

(def remove z/remove)
(def node z/node)

;; ## Find

(defn find
  "Find element satisfying the given predicate by applying the given movement function
   to the initial zipper location."
  ([zloc p?] (find zloc right p?))
  ([zloc f p?] (->> zloc
                 (iterate f)
                 (take-while identity)
                 (drop-while (complement p?))
                 (first))))

(defn find-by-tag
  "Find element with the given tag by applying the given movement function to the initial
   zipper location, defaulting to `right`."
  ([zloc t] (find-by-tag zloc right t))
  ([zloc f t] (find zloc f #(= (tag %) t))))

(defn find-next-by-tag
  "Find next element with the given tag, moving to the right before beginning the search."
  [zloc t]
  (when-let [zloc (right zloc)]
    (find-by-tag zloc right t)))

(defn find-previous-by-tag
  "Find previous element with the given tag, moving to the left before beginning the search."
  [zloc t]
  (when-let [zloc (left zloc)]
    (find-by-tag zloc left t)))

(defn find-token
  "Find token element matching the given predicate by applying the given movement function
   to the initial zipper location, defaulting to `right`."
  ([zloc p?] (find-token zloc right p?))
  ([zloc f p?] (find zloc f (fn [x] (and (= (tag x) :token) (p? (value x)))))))

(defn find-value
  "Find token element whose value matches the given one by applying the given movement
   function to the initial zipper location, defaulting ro `right`."
  ([zloc v] (find-value zloc right v))
  ([zloc f v] (find-token zloc f #(= % v))))