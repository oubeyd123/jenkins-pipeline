def call(String apiPath){
    dir("apis/${apiPath}"){
        sh 'mvn clean -b install'
    }
    def car =sh(
        script:"find apis/${apiPath} -name '*.car' | head -n1 "
        returnstdout:true
    ).trim()
    if (!car){
        error "no .car file was produced for apis/{$apiPath}"
    }
    return car
}
return this
