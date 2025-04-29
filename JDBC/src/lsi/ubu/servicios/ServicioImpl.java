package lsi.ubu.servicios;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Date;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import lsi.ubu.excepciones.AlquilerCochesException;
import lsi.ubu.util.PoolDeConexiones;
import lsi.ubu.util.exceptions.SGBDError;
import lsi.ubu.util.exceptions.oracle.OracleSGBDErrorUtil;
import lsi.ubu.Misc;

public class ServicioImpl implements Servicio {
	private static final Logger LOGGER = LoggerFactory.getLogger(ServicioImpl.class);

	private static final int DIAS_DE_ALQUILER = 4;

	public void alquilar(String nifCliente, String matricula, Date fechaIni, Date fechaFin) throws SQLException {
		PoolDeConexiones pool = PoolDeConexiones.getInstance();

		Connection con = null;
		PreparedStatement st = null;
		ResultSet rs = null;

		/*
		 * El calculo de los dias se da hecho
		 */
		long diasDiff = DIAS_DE_ALQUILER;
		Date fechaFinAlq = new java.util.Date();
		if (fechaFin != null) {
			diasDiff = TimeUnit.MILLISECONDS.toDays(fechaFin.getTime() - fechaIni.getTime());

			if (diasDiff < 1) {
				throw new AlquilerCochesException(AlquilerCochesException.SIN_DIAS);
			}
		}
		else fechaFinAlq = Misc.addDays(fechaIni, DIAS_DE_ALQUILER);

		try {
			con = pool.getConnection();

			// Utilizamos programación defensiva para cada caso
			// Si no existe el NIF del cliente en la base de datos, lanzamos excepción
			st = con.prepareStatement("SELECT NIF FROM clientes WHERE NIF = ?");
			st.setString(1, nifCliente);
			rs = st.executeQuery();
			if (!rs.next()) throw new AlquilerCochesException(AlquilerCochesException.CLIENTE_NO_EXIST);
			st.close();
			rs.close();
			
			// Si no existe la matrícula del vehículo en la base de datos, lanzamos excepción
			st = con.prepareStatement("SELECT id_modelo FROM vehiculos WHERE matricula = ?");
			st.setString(1, matricula); 
			rs = st.executeQuery();
			if (!rs.next()) throw new AlquilerCochesException(AlquilerCochesException.VEHICULO_NO_EXIST);
			st.close();
			rs.close();
			
			// Verificar que el vehículo no está ocupado en las fechas solicitadas.
			// Es un cálculo complejo, bastante más que el que se había planteado inicialmente (realmente no estaba definido, solo era para tener la estructura)
			// Debemos validar que no se cumple ninguna de estas 3 condiciones para confirmar que no hay ningún alquiler para ese vehículo en las fechas solicitadas
			st = con.prepareStatement(
					"SELECT matricula FROM reservas WHERE matricula = ? AND " + 
					"((fecha_ini <= ? AND fecha_fin >= ?) OR " + // Condición 1: la fecha de inicio del alquiler no está dentro del plazo de una reserva
					"(fecha_ini <= ? AND fecha_fin >= ?) OR " + // Condición 2: la fecha de fin no está dentro del plazo de una reserva
					"(fecha_ini >= ? AND fecha_fin <= ?))"); // Condición 3: las fechas no están ya reservadas
			
			st.setString(1, matricula);
			// Condición 1 - La fecha de inicio del alquiler no puede ser mayor que la fecha de inicio y menor que la de fin de otra reserva
			// No puede empezar más tarde de lo que empieza otra si se supone que acaba antes.
			st.setDate(2, new java.sql.Date(fechaIni.getTime()));
			st.setDate(3, new java.sql.Date(fechaIni.getTime()));
			// Condición 2 - La fecha de final del alquiler no puede ser mayor que la fecha de inicio y menor que la de fin de otra reserva (mismo caso que 1)
			// No puede acabar más tarde de lo que empieza otra si se supone que acaba antes.
			st.setDate(4, new java.sql.Date(fechaFinAlq.getTime()));
			st.setDate(5, new java.sql.Date(fechaFinAlq.getTime()));
			// Condición 3 - La fecha de inicio del alquiler no puede ser menor que la de inicio si a su vez, la final es mayor que la de fin de otra reserva.
			// En este caso, la fecha de inicio de la otra reserva estaría dentro del plazo de la reserva a añadir.
			st.setDate(6, new java.sql.Date(fechaIni.getTime()));
			st.setDate(7, new java.sql.Date(fechaFinAlq.getTime()));
			
			// Si hay algún caso que cumpla alguna de las 3 condiciones, lanzamos excepción
			rs = st.executeQuery();
			if (rs.next()) throw new AlquilerCochesException(AlquilerCochesException.VEHICULO_OCUPADO);
			st.close();
			rs.close();
			
			// Insertamos nueva reserva (manejamos correctamente el caso de fechaFin nula)
			st = con.prepareStatement("INSERT into reservas (idReserva, cliente, matricula, fecha_ini, fecha_fin) VALUES (seq_reservas.nextval, ?, ?, ?, ?)");
			st.setString(1, nifCliente); // Corregido: índice 1 en lugar de 0
			st.setString(2, matricula); // Corregido: índice 2 en lugar de 1
			st.setDate(3, new java.sql.Date(fechaIni.getTime())); // Corregido: índice 3 en lugar de 2
			
			// Si fechaFin es null, establecemos el parámetro como NULL
			if (fechaFin != null) {
				st.setDate(4, new java.sql.Date(fechaFin.getTime())); // Corregido: índice 4 en lugar de 3
			} else {
				st.setNull(4, java.sql.Types.DATE);
			}
			
			st.executeUpdate();
			
			// Obtenemos datos del vehículo para generar factura
			st = con.prepareStatement(
					"SELECT m.precio_cada_dia, m.capacidad_deposito, m.tipo_combustible, pc.precio_por_litro, m.id_modelo " +
					"FROM vehiculos v JOIN modelos m ON v.id_modelo = m.id_modelo " +
					"JOIN precio_combustible pc ON m.tipo_combustible = pc.tipo_combustible " +
					"WHERE v.matricula = ?");
			st.setString(1, matricula);
			rs = st.executeQuery();
			
			// Almacenamos precios de alquiler y combustible
			if (rs.next()) {
				BigDecimal precioDia = rs.getBigDecimal(1);
				int capacidadDeposito = rs.getInt(2);
				String tipoCombustible = rs.getString(3);
				BigDecimal precioLitro = rs.getBigDecimal(4);
				int idModelo = rs.getInt(5);
				
				// Realizamos cálculos utilizando las operaciones necesarias con bigDecimal
				BigDecimal importeAlquiler = precioDia.multiply(new BigDecimal(diasDiff));
				BigDecimal importeCombustible = precioLitro.multiply(new BigDecimal(capacidadDeposito));
				BigDecimal importeTotal = importeAlquiler.add(importeCombustible);
				
				st = con.prepareStatement(
						"INSERT INTO facturas (nroFactura, importe, cliente) " +
						"VALUES (seq_num_fact.nextval, ?, ?)");
				st.setBigDecimal(1, importeTotal);
				st.setString(2, nifCliente);
				st.executeUpdate();
				st.close();
				
				// Obtenemos el número de factura generado para generar líneas
				st = con.prepareStatement("SELECT seq_num_fact.currval FROM dual");
				rs = st.executeQuery();
				rs.next();
				int nroFactura = rs.getInt(1);
				rs.close();
				st.close();
				
				// Añadimos línea de factura con coste alquiler
				st = con.prepareStatement(
						"INSERT INTO lineas_factura (nroFactura, concepto, importe) VALUES (?, ?, ?)");
				st.setInt(1, nroFactura);
				st.setString(2, diasDiff + " dias de alquiler, vehiculo modelo " + idModelo + "   ");
				st.setBigDecimal(3, importeAlquiler);
				st.executeUpdate();
				st.close();
				
				// Añadimos línea de factura con coste combustible
				st = con.prepareStatement(
						"INSERT INTO lineas_factura (nroFactura, concepto, importe) VALUES (?, ?, ?)");
				st.setInt(1, nroFactura);
				st.setString(2, "Deposito lleno de " + capacidadDeposito + " litros de " + tipoCombustible + " ");
				st.setBigDecimal(3, importeCombustible);
				st.executeUpdate();
			}
			
			// Confirmamos los cambios
			con.commit();
			

		} catch (SQLException e) {
			if (con!=null) con.rollback();
			if (e instanceof AlquilerCochesException) throw (AlquilerCochesException) e;
			if(new OracleSGBDErrorUtil().checkExceptionToCode(e, SGBDError.FK_VIOLATED)) {
				LOGGER.debug(e.getMessage());
				throw e;
			}
            throw e;

		} finally {
			if(st!= null) st.close();
			if(rs!=null) rs.close();
			if(con!=null) con.close();
		}
	}
}
