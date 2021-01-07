(ns aerospike-clj.mock-client
  (:refer-clojure :exclude [update])
  (:require [clojure.pprint :refer [pprint]]
            [promesa.core :as p]
            [aerospike-clj.client :as client]
            [aerospike-clj.utils])
  (:import (com.aerospike.client AerospikeException ResultCode)))






(def ^:private DEFAULT_SET "__DEFAULT__")

(defn- get-set-name [set-name]
  (if (some? set-name) set-name DEFAULT_SET))

(defn- get-record [state set-name k]
  (get-in state [(get-set-name set-name) k]))

(defn- set-record [state value set-name k]
  (assoc-in state [(get-set-name set-name) k] value))

(defn- delete-record [state set-name k]
  (clojure.core/update state (get-set-name set-name) dissoc k))

(defn record-exists? [state set-name k]
  (some? (get-record state (get-set-name set-name) k)))

(defn- get-generation
  "The number of times the record has been modified.
  Defaults to 0 if the record doesn't exist or the value cannot be determined."
  [state set-name k]
  (:gen (get-record state set-name k) 0))

(defn- get-transcoder [conf]
  (:transcoder conf identity))

(defn- create-record
  ([payload ttl] (create-record payload ttl 1))
  ([payload ttl generation]
   {:payload payload :ttl ttl :gen generation}))

(defn- do-swap [state swap-fn]
  (try
    (swap! state swap-fn)
    (p/resolved true)
    (catch AerospikeException ex
      (p/rejected ex))))

(defn- filter-bins [bins record]
  (clojure.core/update record :payload #(select-keys % bins)))


(defrecord MockClient [state]
  client/IAerospikeClient2
  (get-single [this k set-name] (client/get-single this k set-name {} nil))

  (get-single [this k set-name conf] (client/get-single this k set-name conf nil))

  (get-single [_ k set-name conf _]
    (let [transcoder (get-transcoder conf)]
      (p/resolved (transcoder (get-record @state set-name k)))))

  (get-multiple [this indices set-names]
    (client/get-multiple this indices set-names {}))

  (get-multiple [this indices set-names conf]
    (p/resolved
      (mapv (fn [k set-name] @(client/get-single this k set-name conf)) indices set-names)))

  (get-batch [this batch-reads]
    (client/get-batch this batch-reads {}))

  (get-batch [this batch-reads conf]
    (p/resolved
      (mapv
        (fn [record]
          (let [bins     (if (= [:all] (:bins record)) nil (:bins record))
                get-bins (if bins (partial filter-bins bins) identity)]
            (get-bins @(client/get-single this (:index record) (:set record) conf))))
        batch-reads)))

  (get-single-no-meta [this k set-name]
    (client/get-single this k set-name {:transcoder :payload}))

  (get-single-no-meta [this k set-name bin-names]
    (client/get-single this k set-name {:transcoder :payload} bin-names))

  (exists? [this k set-name]
    (client/exists? this k set-name {}))

  (exists? [_ k set-name _]
    (p/resolved (record-exists? @state set-name k)))

  (exists-batch [this indices]
    (client/exists-batch this indices {}))

  (exists-batch [_ indices _]
    (p/resolved
      (mapv (fn [v] (record-exists? @state (:set v) (:index v))) indices)))

  (put [this k set-name data expiration]
    (client/put this k set-name data expiration {}))

  (put [_ k set-name data expiration conf]
    (let [transcoder (get-transcoder conf)
          new-record (create-record (transcoder data) expiration)
          swap-fn    (fn [current-state] (set-record current-state new-record set-name k))]
      (do-swap state swap-fn)))

  (set-single [this k set-name data expiration]
    (client/set-single this k set-name data expiration {}))

  (set-single [this k set-name data expiration conf]
    (let [generation (get-generation @state set-name k)]
      (client/update this k set-name data generation expiration conf)))

  (add-bins [this k set-name new-data new-expiration]
    (client/add-bins this k set-name new-data new-expiration {}))

  (add-bins [_ k set-name new-data new-expiration conf]
    (let [transcoder (get-transcoder conf)
          swap-fn    (fn [current-state]
                       (if-let [old-data (:payload (get-record current-state set-name k))]
                         (let [merged-data (merge old-data new-data)
                               generation  (get-generation current-state set-name k)
                               new-record  (create-record
                                             (transcoder merged-data) new-expiration generation)]
                           (set-record current-state new-record set-name k))
                         (throw (AerospikeException.
                                  (ResultCode/KEY_NOT_FOUND_ERROR)
                                  (str "Call to `add-bins` on non-existing key '" k "'")))))]
      (do-swap state swap-fn)))

  (put-multiple [this indices set-names payloads expiration-seq]
    (client/put-multiple this indices set-names payloads expiration-seq {}))

  (put-multiple [this indices set-names payloads expiration-seq conf]
    (p/resolved
      (mapv (fn [k set-name payload expiration]
              @(client/put this k set-name payload expiration conf))
            indices set-names payloads expiration-seq)))

  (create [this k set-name data expiration]
    (client/create this k set-name data expiration {}))

  (create [_ k set-name data expiration conf]
    (let [swap-fn (fn [current-state]
                    (when (some? (get-record current-state set-name k))
                      (throw (AerospikeException.
                               (ResultCode/KEY_EXISTS_ERROR)
                               (str "Call to `create` on existing key '" k "'"))))

                    (let [transcoder (get-transcoder conf)
                          new-record (create-record (transcoder data) expiration)]
                      (set-record current-state new-record set-name k)))]
      (do-swap state swap-fn)))

  (replace-only [this k set-name data expiration]
    (client/replace-only this k set-name data expiration {}))

  (replace-only [_ k set-name data expiration conf]
    (let [swap-fn (fn [current-state]
                    (if (some? (get-record current-state set-name k))
                      (let [transcoder (get-transcoder conf)
                            new-record (create-record (transcoder data) expiration)]
                        (set-record current-state new-record set-name k))
                      (AerospikeException.
                        ResultCode/KEY_NOT_FOUND_ERROR
                        (str "Call to `replace-only` on non-existing key '" k "'"))))]
      (do-swap state swap-fn)))

  (update [this k set-name new-record generation new-expiration]
    (client/update this k set-name new-record generation new-expiration {}))

  (update [_ k set-name new-record expected-generation new-expiration conf]
    (let [swap-fn (fn [current-state]
                    (let [current-generation (get-generation current-state set-name k)]
                      (when-not (= current-generation expected-generation)
                        (throw (AerospikeException.
                                 (ResultCode/GENERATION_ERROR)
                                 (str "Record 'generation' count does not match expected value: '"
                                      expected-generation "'"))))

                      (let [transcoder (get-transcoder conf)
                            new-record (create-record
                                         (transcoder new-record) new-expiration (inc current-generation))]
                        (set-record current-state new-record set-name k))))]
      (do-swap state swap-fn)))

  (touch [_ k set-name expiration]
    (let [swap-fn (fn [current-state]
                    (if-let [record (get-record current-state set-name k)]
                      (let [new-record (assoc record :ttl expiration)]
                        (set-record current-state new-record set-name k))
                      (throw (AerospikeException.
                               ResultCode/KEY_NOT_FOUND_ERROR
                               (str "Call to `touch` on non-existing key '" k "'")))))]
      (do-swap state swap-fn)))

  (delete [_ k set-name]
    (let [success? (atom nil)
          swap-fn  (fn [current-state]
                     (if (record-exists? current-state set-name k)
                       (do
                         (reset! success? true)
                         (delete-record current-state set-name k))
                       (do
                         (reset! success? false)
                         current-state)))]
      (do-swap state swap-fn)
      (p/resolved @success?)))

  (delete-bins [this k set-name bin-names new-expiration]
    (client/delete-bins this k set-name bin-names new-expiration {}))

  (delete-bins [_ k set-name bin-names new-expiration _]
    (let [swap-fn (fn [current-state]
                    (if-let [old-data (:payload (get-record current-state set-name k))]
                      (let [merged-data (apply dissoc old-data bin-names)
                            generation  (get-generation current-state set-name k)
                            new-record  (create-record merged-data new-expiration generation)]
                        (set-record current-state new-record set-name k))
                      (throw (AerospikeException.
                               (ResultCode/KEY_NOT_FOUND_ERROR)
                               (str "Call to `delete-bins` on non-existing key '" k "'")))))]
      (do-swap state swap-fn)))

  (operate [this k set-name expiration operations]
    (client/operate this k set-name expiration operations nil))

  (operate [_ _ _ _ _ _]
    (throw (RuntimeException. "Function not implemented")))

  (scan-set [_this _ set-name conf]
    (let [state    (get @state set-name)
          callback (:callback conf)
          bins     (:bins conf)
          get-bins (if bins (partial filter-bins bins) identity)]

      (p/resolved
        (try
          (doseq [[k v] state]
            (when (= (callback k (get-bins v)) :abort-scan)
              (throw (ex-info "" {:abort-scan true}))))
          true
          (catch Exception ex
            (if (:abort-scan (ex-data ex))
              false
              (throw ex)))))))

  (healthy? [_] true)

  (healthy? [_ _] true)

  (get-cluster-stats [_] [[]])

  (stop [_]
    (reset! state {DEFAULT_SET {}})))

(defprotocol ITestAerospike
  (get-state [this] [this set-name]))

(extend-type MockClient
  ITestAerospike
  (get-state
    ([this]
     (get-state this DEFAULT_SET))
    ([this set-name]
     (get @(:state this) set-name))))

(defn expiry-unix [ttl]
  (client/expiry-unix ttl))

(defn create-instance [& _]
  (MockClient. (atom {DEFAULT_SET {}})))
