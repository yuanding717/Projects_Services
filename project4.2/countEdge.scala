var inputfile = sc.textFile("s3://f15-p42/twitter-graph.txt")
var lines = inputfile.flatMap(line => line.split("\n")).distinct()
var edges = lines.map(word => ("numOfEdges", 1))
var num_edges = edges.reduceByKey(_ + _)
num_edges.saveAsTextFile("s3://15619project42ruz/numOfEdges")