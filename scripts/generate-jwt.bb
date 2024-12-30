#!/usr/bin/env bb
;; scripts/generate-jwt.bb

(ns generate-jwt
  {:doc "Babashka script to generate JWT tokens."}
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.java.io :as io]
   [babashka.fs :as fs])
  (:import
   (java.util Base64)
   (java.time Instant)
   (javax.crypto Mac)
   (javax.crypto.spec SecretKeySpec)))

;; Load environment variables from .env file
(defn load-env []
  (let [env-file (fs/file ".env")]
    (when-not (fs/exists? env-file)
      (throw (ex-info "Missing .env file in project root" {:file ".env"})))
    (->> (slurp env-file)
         str/split-lines
         (map #(str/split % #"="))
         (filter #(= 2 (count %)))
         (map (fn [[k v]] [(keyword (str/lower-case k)) v]))
         (into {}))))

(defonce config
  (let [env (load-env)]
    {:jwt-secret (or (:jwt_secret env)
                    (throw (ex-info "JWT_SECRET not found in .env file"
                                  {:required-key :jwt_secret})))}))

(defn b64-url-encode [^String s]
  (-> (Base64/getUrlEncoder)
      (.withoutPadding)
      (.encodeToString (.getBytes s "UTF-8"))))

(defn bytes-to-base64url [bytes]
  (-> (Base64/getUrlEncoder)
      (.withoutPadding)
      (.encodeToString bytes)))

(defn hmac-sha256 [^String data ^String secret]
  (let [mac (Mac/getInstance "HmacSHA256")]
    (.init mac (SecretKeySpec. (.getBytes secret "UTF-8") "HmacSHA256"))
    (.doFinal mac (.getBytes data "UTF-8"))))

(defn sign [header payload secret]
  (let [header-b64 (b64-url-encode (json/generate-string header))
        payload-b64 (b64-url-encode (json/generate-string payload))
        data (str header-b64 "." payload-b64)
        signature (bytes-to-base64url (hmac-sha256 data secret))]
    (str data "." signature)))

(defn -main [& args]
  (let [exp (+ (.getEpochSecond (Instant/now)) 3600)
        header {:alg "HS256" :typ "JWT"}
        payload {:sub "dreamshell-user" :exp exp}
        token (sign header payload (:jwt-secret config))]
    (println token)))

(when (= *file* (System/getProperty "babashka.file"))
  (-main))
