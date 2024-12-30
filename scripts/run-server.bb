#!/usr/bin/env bb
;; scripts/run-server.bb

;; {:deps {http-kit/http-kit {:mvn/version "2.6.0"}
;;         ring/ring-core {:mvn/version "1.9.5"}
;;         cheshire/cheshire {:mvn/version "5.11.0"}
;;         ring/ring-json {:mvn/version "0.5.1"}}}

(ns run-server
  {:doc "Babashka script to manage Docker containers with UUIDs and WebSocket stdio logging."}
  (:require
   [clojure.string :as str]
   [cheshire.core :as json]
   [org.httpkit.server :as http]
   [clojure.java.shell :refer [sh]]
   [babashka.fs :as fs])
  (:import
   (java.util UUID)
   (java.time Instant)
   (java.io FileWriter)))


(defn json-response [body status]
  {:status status
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string body)})

;; ------------------------------------------------------------------------------
;; 1. CONFIGURATION AND INITIAL SETUP
;; ------------------------------------------------------------------------------

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
    {:port 3003
     :jwt-secret (or (:jwt_secret env)
                    (throw (ex-info "JWT_SECRET not found in .env file"
                                  {:required-key :jwt_secret})))
     :docker-image "dreamshell-nixos:latest"
     :sessions-dir (str (fs/path (System/getProperty "user.home") ".dreamshell" "sessions"))
     :error-log (str (fs/path (System/getProperty "user.home") ".dreamshell" "error.log"))}))

;; Ensure necessary directories exist
(fs/create-dirs (:sessions-dir config))
(fs/create-dirs (fs/path (fs/parent (:error-log config))))

;; ------------------------------------------------------------------------------
;; 2. ERROR LOGGING
;; ------------------------------------------------------------------------------

(defn log-error [^String msg exception]
  (let [ts (str (Instant/now))
        message (str ts " | ERROR: " msg " | " (.getMessage exception) "\n")]
    (spit (:error-log config) message :append true)))

;; ------------------------------------------------------------------------------
;; 3. JWT AUTHENTICATION UTILITIES
;; ------------------------------------------------------------------------------

;; Minimal JWT parsing and validation (for demonstration purposes)
(defn parse-jwt [jwt]
  (try
    (let [[_ payload _] (str/split jwt #"\.")]
      (json/parse-string
       (String. (.decode (java.util.Base64/getUrlDecoder) payload))
       true))
    (catch Exception _
      nil)))

(defn valid-jwt?
  "Basic JWT validation: checks structure and presence of 'sub' claim.
   In production, verify signature and claims properly."
  [token secret]
  (when-let [parsed (parse-jwt token)]
    (and (contains? parsed :sub)
         ;; Optionally, check 'exp' claim
         (if-let [exp (:exp parsed)]
           (> exp (-> (Instant/now) .getEpochSecond))
           true))))

(defn require-jwt [handler]
  (fn [req]
    (let [auth-header (get-in req [:headers "authorization"])
          token (when auth-header
                  (second (re-matches #"Bearer\s+(.+)" auth-header)))]
      (if (and token (valid-jwt? token (:jwt-secret config)))
        (handler req)
        (json-response {:error "Unauthorized or invalid token"} 401)))))

;; ------------------------------------------------------------------------------
;; 4. DOCKER INTERACTION FUNCTIONS
;; ------------------------------------------------------------------------------

(defn docker-volume-create
  "Creates a Docker volume with the given name."
  [volume-name]
  (let [res (sh "docker" "volume" "create" volume-name)]
    (if (zero? (:exit res))
      (str/trim (:out res))
      (throw (ex-info "Failed to create Docker volume" {:stderr (:err res)})))))

(defn docker-run
  "Runs a Docker container with the specified image and volume.
   Returns the container ID."
  [image volume-name container-name]
  (let [res (sh "docker" "run" "-d"
                "--name" container-name
                "-v" (str volume-name ":/data")
                image)]
    (if (zero? (:exit res))
      (str/trim (:out res))
      (throw (ex-info "Failed to run Docker container" {:stderr (:err res)})))))

(defn docker-stop
  "Stops a running Docker container."
  [container-id]
  (let [res (sh "docker" "stop" container-id)]
    (if (zero? (:exit res))
      container-id
      (throw (ex-info "Failed to stop Docker container" {:stderr (:err res)})))))

(defn docker-start
  "Starts a stopped Docker container."
  [container-id]
  (let [res (sh "docker" "start" container-id)]
    (if (zero? (:exit res))
      container-id
      (throw (ex-info "Failed to start Docker container" {:stderr (:err res)})))))

(defn docker-rm
  "Removes a Docker container."
  [container-id]
  (let [res (sh "docker" "rm" container-id)]
    (if (zero? (:exit res))
      container-id
      (throw (ex-info "Failed to remove Docker container" {:stderr (:err res)})))))

;; ------------------------------------------------------------------------------
;; 5. IN-MEMORY LOG MANAGEMENT
;; ------------------------------------------------------------------------------

;; Atom to hold in-memory XML logs, mapping UUID to XML content
(defonce in-memory-logs (atom {}))

(defn load-xml-log [uuid]
  "Loads the XML log from disk into memory."
  (let [file-path (str (fs/path (:sessions-dir config) (str uuid "_stdio.xml")))]
    (if (fs/exists? file-path)
      (let [content (slurp file-path)]
        (swap! in-memory-logs assoc uuid content))
      (swap! in-memory-logs assoc uuid ""))))

(defn remove-xml-log [uuid]
  "Removes the XML log from memory."
  (swap! in-memory-logs dissoc uuid))

(defn append-to-xml-log [uuid message]
  "Appends a message to the in-memory XML log and writes to disk."
  (let [escaped-msg (-> message
                        (str/replace "&" "&amp;")
                        (str/replace "<" "&lt;")
                        (str/replace ">" "&gt;")
                        (str/replace "\"" "&quot;")
                        (str/replace "'" "&apos;"))
        timestamp (str (Instant/now))
        xml-entry (str "<message timestamp=\"" timestamp "\">" escaped-msg "</message>\n")]
    ;; Update in-memory log
    (swap! in-memory-logs update uuid str xml-entry)
    ;; Persist to disk
    (let [file-path (str (fs/path (:sessions-dir config) (str uuid "_stdio.xml")))]
      (spit file-path xml-entry :append true))))

;; ------------------------------------------------------------------------------
;; 6. WEBSOCKET HANDLER
;; ------------------------------------------------------------------------------

(defonce websockets (atom {}))

;; (defn ws-handler [req]
;;   (let [uuid (get-in req [:query-params :uuid])]
;;     (if (and uuid (contains? @in-memory-logs uuid))
;;       (http/with-channel req channel
;;         ;; Associate WebSocket with UUID
;;         (swap! websockets assoc channel uuid)

;;         ;; On WebSocket close, remove association
;;         (http/on-close channel (fn [status]
;;                                  (swap! websockets dissoc channel)))

;;         ;; On message received, log and echo it
;;         (http/on-receive channel (fn [data]
;;                                    ;; Log to in-memory and XML
;;                                    (append-to-xml-log uuid data)
;;                                    ;; Echo back to client
;;                                    (http/send! channel (str "echo: " data))))

;;         ;; Optionally, send historical logs to the client upon connection
;;         (when-let [log (get @in-memory-logs uuid)]
;;           (http/send! channel log)))
;;       ;; If UUID is missing or invalid, reject the connection
;;       (json-response {:error "Invalid or missing UUID"} 400))))


;; ------------------------------------------------------------------------------
;; 7. API HANDLERS
;; ------------------------------------------------------------------------------

;; 7.1 Start a new Docker container
(defn start-new-container-handler [req]
  (try
    ;; Run Docker container
    (let [uuid (str (UUID/randomUUID))
          volume-name (str uuid "-vol")
          container-name (str "container-" uuid)
          container-id (docker-run (:docker-image config) volume-name container-name)]

      ;; Create and load XML log into memory
      (append-to-xml-log uuid "Container started.")

      ;; Respond with container details
      (json-response
       {:status "ok"
        :uuid uuid
        :container-id container-id
        :stdio-url (str "ws://localhost:" (:port config) "/ws?uuid=" uuid)}
       200))
    (catch Exception e
      (log-error "Error in start-new-container-handler" e)
      (json-response {:error (.getMessage e)} 500))))

;; 7.2 Restart an existing Docker container
(defn restart-container-handler [req]
  (try
    (let [body (:body req)
          uuid (:uuid body)]
      (if (and uuid (contains? @in-memory-logs uuid))
        (let [container-name (str "container-" uuid)]
          ;; Stop the container if running
          (docker-stop container-name)
          ;; Start the container
          (docker-start container-name)

          ;; Log the restart
          (append-to-xml-log uuid "Container restarted.")

          ;; Respond with new stdio URL
          (json-response
           {:status "ok"
            :uuid uuid
            :container-id container-name
            :stdio-url (str "ws://localhost:" (:port config) "/ws?uuid=" uuid)}
           200))
        (json-response {:error "Invalid or unknown UUID"} 400)))
    (catch Exception e
      (log-error "Error in restart-container-handler" e)
      (json-response {:error (.getMessage e)} 500))))

;; 7.3 Terminate an existing Docker container
(defn terminate-container-handler [req]
  (try
    (let [body (:body req)
          uuid (:uuid body)]
      (if (and uuid (contains? @in-memory-logs uuid))
        (let [container-name (str "container-" uuid)]

          ;; Notify WebSocket client
          (doseq [[ch ch-uuid] @websockets]
            (when (= ch-uuid uuid)
              (http/send! ch (str "Container " uuid " is about to be terminated."))
              (http/close ch)))

          ;; Stop the container
          (docker-stop container-name)

          ;; Log termination
          (append-to-xml-log uuid "Container terminated.")

          ;; Remove in-memory log and delete XML file
          (remove-xml-log uuid)
          (let [file-path (str (fs/path (:sessions-dir config) (str uuid "_stdio.xml")))]
            (fs/delete file-path))

          ;; Respond to API client
          (json-response
           {:status "ok"
            :message (str "Container " uuid " terminated.")}
           200))
        (json-response {:error "Invalid or unknown UUID"} 400)))
    (catch Exception e
      (log-error "Error in terminate-container-handler" e)
      (json-response {:error (.getMessage e)} 500))))

;; 7.4 Delete an existing Docker container
(defn delete-container-handler [req]
  (try
    (let [body (:body req)
          uuid (:uuid body)]
      (if (and uuid (contains? @in-memory-logs uuid))
        (let [container-name (str "container-" uuid)
              inspect-res (sh "docker" "inspect" "-f" "{{.State.Running}}" container-name)
              running? (str/trim (:out inspect-res))]
          (if (= running? "true")
              (json-response
                {:error (str "Container " uuid " is still running. Stop it first.")}
                400)
            (do
              ;; Remove container
              (docker-rm container-name)

              ;; Remove volume
              (sh "docker" "volume" "rm" (str uuid "-vol"))

              ;; Remove XML log if exists
              (remove-xml-log uuid)
              (let [file-path (str (fs/path (:sessions-dir config) (str uuid "_stdio.xml")))]
                (when (fs/exists? file-path)
                  (fs/delete file-path)))

              ;; Respond to API client
              (json-response
               {:status "ok"
                :message (str "Container " uuid " and its volume removed.")}
               200))))
        (json-response {:error "Invalid or unknown UUID"} 400)))
    (catch Exception e
      (log-error "Error in delete-container-handler" e)
      (json-response {:error (.getMessage e)} 500))))

;; ------------------------------------------------------------------------------
;; 8. OPENAPI SPECIFICATION
;; ------------------------------------------------------------------------------

(def openapi-spec
  {:openapi "3.0.0"
   :info {:title "DreamShell Docker Manager API"
          :version "1.0.0"}
   :paths
   {"/api/start" {:post {:summary "Start a new Docker container"
                         :operationId "startContainer"
                         :responses {200 {:description "Container started successfully"
                                          :content {"application/json"
                                                    {:schema {:type "object"
                                                              :properties {:status {:type "string"}
                                                                           :uuid {:type "string"}
                                                                           :container-id {:type "string"}
                                                                           :stdio-url {:type "string"}}}}}}}}}
    "/api/restart" {:post {:summary "Restart an existing Docker container"
                           :operationId "restartContainer"
                           :requestBody {:required true
                                         :content {"application/json"
                                                   {:schema {:type "object"
                                                             :properties {:uuid {:type "string"}}}}}}
                           :responses {200 {:description "Container restarted successfully"
                                            :content {"application/json"
                                                      {:schema {:type "object"
                                                                :properties {:status {:type "string"}
                                                                             :uuid {:type "string"}
                                                                             :container-id {:type "string"}
                                                                             :stdio-url {:type "string"}}}}}}}}}
    "/api/terminate" {:post {:summary "Terminate a running Docker container"
                             :operationId "terminateContainer"
                             :requestBody {:required true
                                           :content {"application/json"
                                                     {:schema {:type "object"
                                                               :properties {:uuid {:type "string"}}}}}}
                             :responses {200 {:description "Container terminated successfully"
                                              :content {"application/json"
                                                        {:schema {:type "object"
                                                                  :properties {:status {:type "string"}
                                                                               :message {:type "string"}}}}}}}}}
    "/api/delete" {:post {:summary "Delete a Docker container (must be stopped)"
                          :operationId "deleteContainer"
                          :requestBody {:required true
                                        :content {"application/json"
                                                  {:schema {:type "object"
                                                            :properties {:uuid {:type "string"}}}}}}
                          :responses {200 {:description "Container and volume deleted successfully"
                                           :content {"application/json"
                                                     {:schema {:type "object"
                                                               :properties {:status {:type "string"}
                                                                            :message {:type "string"}}}}}}}}}
    "/ws" {:get {:summary "WebSocket for container stdio"
                :operationId "websocketStdio"
                :parameters [{:name "uuid"
                             :in "query"
                             :required true
                             :schema {:type "string"}}]
                :responses {101 {:description "Switching Protocols"}}}}}})

(defn openapi-spec-handler [_]
  (json-response openapi-spec 200))

;; ------------------------------------------------------------------------------
;; 9. ROUTING WITH MIDDLEWARE
;; ------------------------------------------------------------------------------

(defn parse-json-body [req]
  (when-let [body (:body req)]
    (json/parse-string (slurp body) true)))

(defn routes [{:keys [uri request-method] :as req}]
  (let [handler (case [request-method uri]
                  [:get "/openapi.json"] openapi-spec-handler
                  [:post "/api/start"] start-new-container-handler
                  [:post "/api/restart"] restart-container-handler
                  [:post "/api/terminate"] terminate-container-handler
                  [:post "/api/delete"] delete-container-handler
                  ;; [:get "/ws"] ws-handler
                  nil)]
    (if handler
      (handler (cond-> req
                (= request-method :post) (assoc :body (parse-json-body req))))
      (json-response {:error "Not found"} 404))))

(defn wrap-auth [handler]
  (fn [req]
    (let [auth-header (get-in req [:headers "authorization"])
          token (when auth-header
                 (second (re-matches #"Bearer\s+(.+)" auth-header)))]
      (if (and token (valid-jwt? token (:jwt-secret config)))
        (handler req)
        (json-response {:error "Unauthorized"} 401)))))

(def app
  (wrap-auth routes))

;; ------------------------------------------------------------------------------
;; 10. ENTRYPOINT
;; ------------------------------------------------------------------------------

(defn -main [& _args]
  (println "Starting DreamShell server on port" (:port config) "...")
  (let [server (http/run-server app {:port (:port config)})]
    ;; Keep the process alive
    @(promise)))


;; Execute -main when run as a script
(when (= *file* (System/getProperty "babashka.file"))
  (-main))
