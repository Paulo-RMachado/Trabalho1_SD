# Trabalho1_SD


Terminal 1
java -cp target/classes com.furb.br.client.Client -p 5001 -d 100000 -i 3000 -drift 1.00 -peers localhost:5001,localhost:5002,localhost:5003

Terminal 2
java -cp target/classes com.furb.br.client.Client -p 5002 -d 100000 -i 3000 -drift 1.03 -peers localhost:5001,localhost:5002,localhost:5003

Terminal 3
java -cp target/classes com.furb.br.client.Client -p 5003 -d 100000 -i 3000 -drift 0.97 -peers localhost:5001,localhost:5002,localhost:5003