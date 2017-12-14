(ns leiningen.protobuf
  (:use [clojure.string :only [join]]
        [leiningen.javac :only [javac]]
        [leinjacker.eval :only [in-project]]
        [leiningen.core.user :only [leiningen-home]]
        [leiningen.core.main :only [abort]])
  (:import
   (java.io File)
   (java.net URL))
  (:require [clojure.java.io :as io]
            [fs.core :as fs]
            [fs.compression :as fs-zip]
            [me.raynes.conch.low-level :as sh]))

(set! *warn-on-reflection* true)

(def ^File cache
  "Protobuf-specific cache directory for assorted uses"
  (io/file (leiningen-home) "cache" "lein-protobuf"))

(def default-proto-path
  "Default location for project's `.proto` files"
  "resources/proto")

(def default-version
  "Default version of protobuf kit if `:protobuf-version` is not specified."
  "2.6.1")

(defn version
  "Version of Google Protocol Compiler to use

  If `:protobuf-version` is not in project, defaults to `default-version`."
  [project]
  (or (:protobuf-version project) default-version))

(defn ^File zipfile
  "Where to put/find the downloaded ZIP file containing the selected
  version of the Protocol Compiler"
  [project]
  (io/file cache (format "protobuf-%s.zip" (version project))))

(defn ^File srcdir
  "Directory under cache for storing sources"
  [project]
  (io/file cache (str "protobuf-" (version project))))

(defn ^File protoc
  "Identifies `protoc` executable"
  [project]
  (if (:protoc project)
    (io/file (:protoc project))
    (io/file (srcdir project) "src" "protoc")))

(defn ^URL url
  "Web-source for Protocol Compiler to be downloaded"
  [project]
  (URL.
   (format "https://github.com/google/protobuf/releases/download/v%1$s/protobuf-%1$s.zip"
     (version project))))

(defn proto-path
  "Where the project's proto files are stored."
  [project]
  (io/file (get project :proto-path default-proto-path)))

(def ^:dynamic *compile-protobuf?* true)

(defn target [project]
  (doto (io/file (:target-path project))
    .mkdirs))

(defn extract-dependencies
  "Extract all files proto depends on into dest."
  [project ^File proto-path protos ^File dest]
  (in-project (dissoc project :prep-tasks)
    [proto-path (.getPath proto-path)
     dest (.getPath dest)
     protos protos]
    (ns (:require [clojure.java.io :as io]))
    (letfn [(dependencies [proto-file]
              (when (.exists proto-file)
                (for [line (line-seq (io/reader proto-file))
                      :when (.startsWith line "import")]
                  (second (re-matches #".*\"(.*)\".*" line)))))]
      (loop [deps (mapcat #(dependencies (io/file proto-path %)) protos)]
        (when-let [[dep & deps] (seq deps)]
          (let [proto-file (io/file dest dep)]
            (if (or (.exists (io/file proto-path dep))
                    (.exists proto-file))
              (recur deps)
              (do (.mkdirs (.getParentFile proto-file))
                  (when-let [resource (io/resource (str "proto/" dep))]
                    (io/copy (io/reader resource) proto-file))
                  (recur (concat deps (dependencies proto-file)))))))))))

(defn modtime
  "Return the last modification time of a file or directory.

  If a single file, its mod-time; if a directory, the most recent of
  the mod-times"
  [f]
  (let [files (if (fs/directory? f)
                (->> f io/file file-seq rest)
                [f])]
    (if (empty? files)
      0
      (apply max (map fs/mod-time files)))))

(defn proto-file?
  "Identify a `.proto` file by extension.

  **Note:** the file can't be hidden, either."
  [^File file]
  (let [^String name (.getName file)]
    (and (.endsWith name ".proto")
         (not (.startsWith name ".")))))

(defn proto-files
  "Extract the name (and relative path) of `.proto` files below `dir`."
  [^File dir]
  (for [^File file (rest (file-seq dir)) :when (proto-file? file)]
    (.substring (.getPath file) (inc (count (.getPath dir))))))

(defn fetch
  "Fetch protocol-buffer source and unzip it."
  [project]
  (println "************************************************************")
  (println "**** (fetch project) ***************************************")
  (println "************************************************************")
  (let [zipfile (zipfile project)
        srcdir  (srcdir project)]
    (when-not (.exists zipfile)
      (.mkdirs cache)
      (println (format "Downloading %s to %s" (.getName zipfile) zipfile))
      (with-open [stream (.openStream (url project))]
        (io/copy stream zipfile)))
    (when-not (.exists srcdir)
      (println (format "Unzipping %s to %s" zipfile srcdir))
      (fs-zip/unzip zipfile cache))))

(defn build-protoc
  "Compile protoc from source."
  [project]
  (let [srcdir (srcdir project)
        _ (println "srcdir: " srcdir)
        protoc (protoc project)
        _ (println "protoc: " protoc)]
    (when-not (.exists protoc)
      (fetch project)
      (fs/chmod "+x" (io/file srcdir "autogen.sh"))
      (sh/stream-to-out (sh/proc "./autogen.sh" :dir srcdir) :out)
      (println "Configuring protoc")
      (fs/chmod "+x" (io/file srcdir "configure"))
      (sh/stream-to-out (sh/proc "./configure" :dir srcdir) :out)
      (println "Running 'make'")
      (sh/stream-to-out (sh/proc "make" :dir srcdir) :out))))

#_(defn protoc [project]
  "Get :protoc argument from project."
  (:protoc project))

(defn compile-protobuf
  "Create .java and .class files from the provided .proto files."
  ([project protos]
     (compile-protobuf project protos (io/file (target project) "protosrc")))
  ([project protos ^File dest]
   (println "************************************************************")
   (println "******** (compile-protobuf project " protos "  " dest "*****")
   (println "************************************************************")

   (let [target     (target project)
         ^File class-dest (io/file target "classes")
         ^File proto-dest (io/file target "proto")
         proto-path (proto-path project)
         protoc (or (protoc project) proto-path)]
     (when (or (> (modtime proto-path) (modtime dest))
               (> (modtime proto-path) (modtime class-dest)))
       (binding [*compile-protobuf?* false]
         (fs/mkdirs target)
         (fs/mkdirs class-dest)
         (fs/mkdirs proto-dest)
         (.mkdirs dest)
         (extract-dependencies project proto-path protos proto-dest)
         (doseq [proto protos]
           (let [args (into [] (concat  [(str protoc) proto
                                         (str "--java_out=" (.getAbsoluteFile dest)) "-I."]
                                        (map (fn [^File f]
                                               (str "-I" (.getAbsoluteFile f)))
                                             [proto-dest proto-path])))]
             (println " > " (join " " args))
             (let [all-the-args (concat args [:dir (str proto-path)])
                   _ (println "all-the-args> " all-the-args)
                   result (apply sh/proc all-the-args)]
               (when-not (= (sh/exit-code result) 0)
                 (abort "ERROR:" (sh/stream-to-string result :err))))))
         (javac (-> project
                    (update-in [:java-source-paths] concat [(.getPath dest)])
                    (update-in [:javac-options] concat ["-Xlint:none"]))))))))

(defn compile-google-protobuf
  "Compile com.google.protobuf.*"
  [project]
  (fetch project)
  (let [srcdir (srcdir project)
        descriptor "google/protobuf/descriptor.proto"
        src (io/file srcdir "src" descriptor)
        dest (io/file (proto-path project) descriptor)]
    (.mkdirs (.getParentFile dest))
    (when (> (modtime src) (modtime dest))
      (io/copy src dest))
    (compile-protobuf project [descriptor]
                      (io/file srcdir "java" "src" "main" "java"))))

(defn protobuf
  "Task for compiling protobuf libraries."
  [project & files]
  (let [files (or (seq files)
                  (proto-files (proto-path project)))
        protoc (protoc project)]
    (when-not (.exists protoc)
      (build-protoc project))
    (when (and (= "protobuf" (:name project)))
      (compile-google-protobuf project))
    (compile-protobuf project files)))
